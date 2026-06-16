const dgram = require("dgram");
const { performance } = require("perf_hooks");
const os = require("os");
const http = require("http");
const fs = require("fs");
const { spawn, execSync, execFileSync } = require("child_process");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = 45678;
const WS_PORT = 45679;
const DISCOVER_MSG = "AR3TE_DISCOVER";
const VIDEO_BINARY_TYPE = 1;
const AUDIO_BINARY_TYPE = 2;
const AUDIO_SAMPLE_RATE = 48000;
const AUDIO_CHANNELS = 2;
const AUDIO_FRAME_DURATION_MS = 20;
const AUDIO_DEVICE_CANDIDATES = [
  process.env.AR3TE_AUDIO_DEVICE,
  "virtual-audio-capturer",
  "CABLE Output (VB-Audio Virtual Cable)",
  "Stereo Mix",
].filter(Boolean);
const PORT_RECLAIM_TIMEOUT_MS = 10000;
const PORT_RECLAIM_POLL_MS = 200;
let discoverySocket = null;
let discoveryBindAttempts = 0;

function getPidsOnPort(port) {
  const pids = new Set();

  if (process.platform === "win32") {
    try {
      const output = execSync(`netstat -ano | findstr ":${port}"`, {
        encoding: "utf8",
        windowsHide: true,
      });
      for (const line of output.split(/\r?\n/)) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        const pid = trimmed.split(/\s+/).pop();
        if (pid && /^\d+$/.test(pid) && pid !== String(process.pid)) {
          pids.add(pid);
        }
      }
    } catch {
      // findstr exits 1 when nothing matches
    }
    return pids;
  }

  for (const proto of ["tcp", "udp"]) {
    try {
      const output = execSync(`lsof -ti :${port} -s${proto.toUpperCase()}:LISTEN`, {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      });
      for (const pid of output.split(/\r?\n/)) {
        if (pid && pid !== String(process.pid)) {
          pids.add(pid);
        }
      }
    } catch {
      try {
        const output = execSync(`lsof -ti :${port}`, {
          encoding: "utf8",
          stdio: ["ignore", "pipe", "ignore"],
        });
        for (const pid of output.split(/\r?\n/)) {
          if (pid && pid !== String(process.pid)) {
            pids.add(pid);
          }
        }
      } catch {
        // No process on this port
      }
    }
  }

  return pids;
}

function killPid(pid) {
  try {
    if (process.platform === "win32") {
      execSync(`taskkill /PID ${pid} /F /T`, { stdio: "ignore", windowsHide: true });
    } else {
      process.kill(Number(pid), "SIGTERM");
    }
  } catch {
    // Process already exited
  }
}

function sleepSync(ms) {
  const shared = new SharedArrayBuffer(4);
  const view = new Int32Array(shared);
  Atomics.wait(view, 0, 0, ms);
}

function waitForPortsToBeFree(ports, timeoutMs = PORT_RECLAIM_TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const pids = new Set();
    for (const port of ports) {
      for (const pid of getPidsOnPort(port)) {
        pids.add(pid);
      }
    }
    if (pids.size === 0) {
      return true;
    }
    sleepSync(PORT_RECLAIM_POLL_MS);
  }
  return false;
}

function killExistingInstances() {
  const ports = [PORT, WS_PORT];
  const seen = new Set();
  const deadline = Date.now() + PORT_RECLAIM_TIMEOUT_MS;

  while (Date.now() < deadline) {
    const pids = new Set();
    for (const port of ports) {
      for (const pid of getPidsOnPort(port)) {
        pids.add(pid);
      }
    }

    for (const pid of pids) {
      if (seen.has(pid)) {
        continue;
      }
      seen.add(pid);
      killPid(pid);
    }

    if (waitForPortsToBeFree(ports, PORT_RECLAIM_POLL_MS)) {
      break;
    }

    sleepSync(PORT_RECLAIM_POLL_MS);
  }

  if (process.platform === "win32") {
    try {
      execSync("taskkill /IM Capture.exe /F", { stdio: "ignore", windowsHide: true });
    } catch {
      // No capture process running
    }
  } else {
    try {
      execSync("pkill -f Capture.exe", { stdio: "ignore" });
    } catch {
      // No capture process running
    }
  }

  if (seen.size > 0) {
    console.log(`Stopped ${seen.size} existing server instance(s)`);
  }

  if (!waitForPortsToBeFree(ports, PORT_RECLAIM_POLL_MS)) {
    console.warn(
      `Timed out waiting for ports ${ports.join(", ")} to become free; ` +
        "continuing with startup anyway"
    );
  }
}

killExistingInstances();

function getLocalIpv4() {
  const interfaces = os.networkInterfaces();
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries) {
      if (entry.family === "IPv4" && !entry.internal) {
        return entry.address;
      }
    }
  }
  return "127.0.0.1";
}

const machineName = os.hostname();
const localIp = getLocalIpv4();

// --- UDP Discovery ---
function createDiscoverySocket() {
  const socket = dgram.createSocket("udp4");

  socket.on("message", (message, remote) => {
    if (message.toString() !== DISCOVER_MSG) {
      return;
    }

    const payload = JSON.stringify({
      type: "ar3te",
      name: machineName,
      host: localIp,
      port: PORT,
      wsPort: WS_PORT,
    });

    socket.send(payload, remote.port, remote.address);
  });

  socket.on("error", (error) => {
    if (error.code === "EADDRINUSE" && discoveryBindAttempts < 5) {
      console.warn(`Port ${PORT} is busy, retrying discovery bind...`);
      killExistingInstances();
      try {
        socket.close();
      } catch {
        // Socket may already be closing or closed.
      }
      const retryDelay = Math.min(1000, 200 * discoveryBindAttempts);
      setTimeout(bindDiscoverySocket, retryDelay);
      return;
    }

    console.error(error.code === "EADDRINUSE" ? `Port ${PORT} is already in use.` : error);
    process.exit(1);
  });

  socket.on("listening", () => {
    socket.setBroadcast(true);
    discoverySocket = socket;
    console.log("AR3TE host server running");
    console.log(`  Machine: ${machineName}`);
    console.log(`  UDP Discovery: ${localIp}:${PORT}`);
    console.log(`  WebSocket Stream: ws://${localIp}:${WS_PORT}`);
  });

  return socket;
}

function bindDiscoverySocket() {
  discoveryBindAttempts += 1;
  discoverySocket = createDiscoverySocket();
  discoverySocket.bind(PORT);
}

bindDiscoverySocket();

// --- GPU H.264 Capture Process ---
const ffmpegCandidates = [
  process.env.FFMPEG_PATH,
  "ffmpeg",
  path.join(
    process.env.LOCALAPPDATA || "",
    "Microsoft",
    "WinGet",
    "Packages",
    "Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8weky3d8bbwe",
    "ffmpeg-7.1-essentials_build",
    "bin",
    "ffmpeg.exe"
  ),
].filter(Boolean);

const captureEncoders = ["h264_nvenc"];
let captureProcess = null;
let rawCaptureProcess = null;
let cursorCaptureProcess = null;
let audioCaptureProcess = null;
let inputProcess = null;
let shuttingDown = false;
let activeMonitorIndex = 0;
let activeMonitorInfo = null;
let activeVideoInfo = null;
let ffmpegPath = null;
let nalBuffer = Buffer.alloc(0);
let currentAccessUnit = [];
let currentAccessUnitHasIdr = false;
let lastSps = null;
let lastPps = null;
let lastKeyframe = null;
let lastVideoConfig = null;
let sentVideoConfig = false;
let loggedFirstFrame = false;
let restartRequested = false;
let rawCaptureStderrBuffer = "";
let audioOggBuffer = Buffer.alloc(0);
let audioPacketParts = [];
let currentAudioState = "starting";
let lastAudioConfig = null;
let sentAudioConfig = false;
let lastAudioStatus = null;
let audioDeviceName = null;
let audioDeviceIndex = -1;
let lastCursorMessage = null;
let currentCaptureMethod = "";
let directCaptureAllowed = true;
let wss = null;
const DEBUG_PACING = process.env.AR3TE_DEBUG_PACING !== "0";
const TARGET_FRAME_INTERVAL_MS = 1000 / 60;
let videoSendQueue = [];
let videoSendTimer = null;
let lastVideoSendMs = 0;
let nextVideoSendAt = 0;
let pacingStats = {
  accessUnits: 0,
  bytes: 0,
  lastLogMs: Date.now(),
  lastSendMs: 0,
  maxGapMs: 0,
  minGapMs: Number.POSITIVE_INFINITY,
  gapSumMs: 0,
  gapCount: 0,
};

function findExecutable(candidates) {
  for (const candidate of candidates) {
    if (!candidate) continue;
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      const found = execSync(`where ${candidate}`, {
        encoding: "utf8",
        windowsHide: true,
      })
        .split(/\r?\n/)
        .map((line) => line.trim())
        .find(Boolean);
      if (found && fs.existsSync(found)) {
        return found;
      }
    } catch {
      // Try the next candidate.
    }
  }

  return null;
}

function getMonitorInfo(index) {
  const script = [
    "$ProgressPreference = 'SilentlyContinue'",
    "Add-Type -AssemblyName System.Windows.Forms",
    "$screens = [System.Windows.Forms.Screen]::AllScreens",
    "$rows = @()",
    "for ($i = 0; $i -lt $screens.Length; $i++) {",
    "  $screen = $screens[$i]",
    "  $b = $screen.Bounds",
    "  $rows += [pscustomobject]@{ DeviceName = $screen.DeviceName; Primary = $screen.Primary; X = $b.X; Y = $b.Y; Width = $b.Width; Height = $b.Height }",
    "}",
    "$rows | ConvertTo-Json -Compress",
  ].join("; ");

  const encodedScript = Buffer.from(script, "utf16le").toString("base64");
  const output = execSync(`powershell.exe -NoProfile -EncodedCommand ${encodedScript}`, {
    encoding: "utf8",
    windowsHide: true,
  }).trim();

  const monitorRows = JSON.parse(output);
  const monitors = (Array.isArray(monitorRows) ? monitorRows : [monitorRows])
    .map((row) => {
      return {
        deviceName: row.DeviceName,
        primary: Boolean(row.Primary),
        x: Number(row.X),
        y: Number(row.Y),
        width: Number(row.Width),
        height: Number(row.Height),
      };
    })
    .filter((monitor) => [monitor.x, monitor.y, monitor.width, monitor.height].every(Number.isFinite))
    .sort((a, b) => {
      if (a.primary !== b.primary) {
        return a.primary ? -1 : 1;
      }
      if (a.x !== b.x) {
        return a.x - b.x;
      }
      return a.y - b.y;
    });

  const monitor = monitors[index];
  if (!monitor) {
    throw new Error(`Invalid monitor index ${index + 1}. Available monitors: ${monitors.length}`);
  }
  return monitor;
}

function probeMonitorForDirectCapture(index) {
  const captureExe = path.join(__dirname, "Capture.exe");
  const output = execFileSync(
    captureExe,
    ["--probe", "--monitor", String(index + 1)],
    {
      encoding: "utf8",
      windowsHide: true,
    }
  ).trim();

  const probe = JSON.parse(output);
  if (
    !probe ||
    !Number.isInteger(probe.adapterIndex) ||
    !Number.isInteger(probe.outputIndex) ||
    !Number.isFinite(probe.x) ||
    !Number.isFinite(probe.y) ||
    !Number.isFinite(probe.width) ||
    !Number.isFinite(probe.height)
  ) {
    throw new Error(`Invalid probe data for monitor ${index + 1}`);
  }

  return probe;
}

function resetStreamState() {
  nalBuffer = Buffer.alloc(0);
  currentAccessUnit = [];
  currentAccessUnitHasIdr = false;
  lastSps = null;
  lastPps = null;
  lastKeyframe = null;
  lastVideoConfig = null;
  sentVideoConfig = false;
  loggedFirstFrame = false;
  lastCursorMessage = null;
  resetPacingStats();
  resetVideoSendPacer();
}

function resetAudioStreamState() {
  if ((lastAudioConfig || lastAudioStatus) && wss) {
    broadcastText(JSON.stringify({ type: "audio_reset" }));
  }
  audioOggBuffer = Buffer.alloc(0);
  audioPacketParts = [];
  lastAudioConfig = null;
  sentAudioConfig = false;
  lastAudioStatus = null;
  audioDeviceName = null;
  audioDeviceIndex = -1;
}

function stopRawCaptureProcess() {
  if (rawCaptureProcess && !rawCaptureProcess.killed) {
    rawCaptureProcess.kill();
  }
  rawCaptureProcess = null;
}

function stopCursorCaptureProcess() {
  if (cursorCaptureProcess && !cursorCaptureProcess.killed) {
    cursorCaptureProcess.kill();
  }
  cursorCaptureProcess = null;
}

function stopCaptureProcess() {
  stopRawCaptureProcess();
  stopCursorCaptureProcess();
  if (captureProcess && !captureProcess.killed) {
    captureProcess.kill();
  }
}

function stopAudioCaptureProcess() {
  if (audioCaptureProcess && !audioCaptureProcess.killed) {
    audioCaptureProcess.kill();
  }
  audioCaptureProcess = null;
}

function sendAudioStatus(state, message) {
  currentAudioState = state;
  lastAudioStatus = {
    type: "audio_status",
    state,
    message,
  };
  broadcastText(JSON.stringify(lastAudioStatus));
}

function sendAudioConfig() {
  if (!lastAudioConfig || sentAudioConfig) {
    return;
  }
  console.log(
    `Sending audio_config ${lastAudioConfig.sample_rate}Hz ` +
      `${lastAudioConfig.channels}ch opus frame=${lastAudioConfig.frame_duration_ms}ms`
  );
  broadcastText(JSON.stringify(lastAudioConfig));
  sentAudioConfig = true;
}

function emitAudioPacket(packet) {
  if (!packet || packet.length === 0) {
    return;
  }
  if (!lastAudioConfig) {
    return;
  }
  if (!sentAudioConfig) {
    sendAudioConfig();
  }
  const payload = Buffer.concat([Buffer.from([AUDIO_BINARY_TYPE]), Buffer.from(packet)]);
  if (wss) {
    wss.clients.forEach((client) => {
      if (client.readyState === 1) {
        client.send(payload);
      }
    });
  }
}

function parseOpusHead(packet) {
  if (!packet || packet.length < 19) {
    return null;
  }
  if (packet.slice(0, 8).toString("ascii") !== "OpusHead") {
    return null;
  }

  return {
    type: "audio_config",
    codec: "audio/opus",
    sample_rate: packet.readUInt32LE(12),
    channels: packet.readUInt8(9),
    pre_skip: packet.readUInt16LE(10),
    output_gain: packet.readUInt16LE(16),
    channel_mapping_family: packet.readUInt8(18),
    frame_duration_ms: AUDIO_FRAME_DURATION_MS,
    head: packet.toString("base64"),
  };
}

function handleAudioPacket(packet) {
  if (!packet || packet.length === 0) {
    return;
  }

  if (packet.slice(0, 8).toString("ascii") === "OpusHead") {
    const config = parseOpusHead(packet);
    if (config) {
      lastAudioConfig = config;
      sentAudioConfig = false;
      sendAudioConfig();
      sendAudioStatus("active", "Desktop audio connected");
    }
    return;
  }

  if (packet.slice(0, 8).toString("ascii") === "OpusTags") {
    return;
  }

  if (!lastAudioConfig) {
    return;
  }

  emitAudioPacket(packet);
}

function parseOggAudioStream(chunk) {
  audioOggBuffer = Buffer.concat([audioOggBuffer, chunk]);

  while (audioOggBuffer.length >= 27) {
    const signatureIndex = audioOggBuffer.indexOf("OggS");
    if (signatureIndex < 0) {
      audioOggBuffer = audioOggBuffer.slice(Math.max(0, audioOggBuffer.length - 3));
      return;
    }

    if (signatureIndex > 0) {
      audioOggBuffer = audioOggBuffer.slice(signatureIndex);
    }

    if (audioOggBuffer.length < 27) {
      return;
    }

    const segmentCount = audioOggBuffer.readUInt8(26);
    const headerLength = 27 + segmentCount;
    if (audioOggBuffer.length < headerLength) {
      return;
    }

    let pageLength = headerLength;
    for (let i = 0; i < segmentCount; i++) {
      pageLength += audioOggBuffer.readUInt8(27 + i);
    }

    if (audioOggBuffer.length < pageLength) {
      return;
    }

    const page = audioOggBuffer.slice(0, pageLength);
    audioOggBuffer = audioOggBuffer.slice(pageLength);

    let offset = 27 + segmentCount;
    for (let i = 0; i < segmentCount; i++) {
      const segmentLength = page.readUInt8(27 + i);
      if (segmentLength > 0) {
        audioPacketParts.push(page.slice(offset, offset + segmentLength));
      } else if (audioPacketParts.length === 0) {
        audioPacketParts.push(Buffer.alloc(0));
      }
      offset += segmentLength;

      if (segmentLength < 255) {
        const packet = Buffer.concat(audioPacketParts);
        audioPacketParts = [];
        handleAudioPacket(packet);
      }
    }
  }
}

function resetVideoSendPacer() {
  videoSendQueue = [];
  if (videoSendTimer) {
    clearTimeout(videoSendTimer);
    videoSendTimer = null;
  }
  lastVideoSendMs = 0;
  nextVideoSendAt = 0;
}

function enqueueVideoFrame(frame) {
  videoSendQueue.push(frame);
  if (videoSendTimer) {
    return;
  }

  if (nextVideoSendAt === 0) {
    flushOneVideoFrame();
    nextVideoSendAt = performance.now() + TARGET_FRAME_INTERVAL_MS;
  }

  scheduleVideoSend();
}

function flushOneVideoFrame() {
  if (videoSendQueue.length === 0) {
    return;
  }
  const frame = videoSendQueue.shift();
  lastVideoSendMs = Date.now();
  sendBinaryImmediate(frame);
}

function scheduleVideoSend() {
  if (videoSendTimer || videoSendQueue.length === 0) {
    return;
  }
  const delay = Math.max(0, nextVideoSendAt - performance.now());
  videoSendTimer = setTimeout(fireVideoSend, delay > 0 ? delay : 0);
}

function fireVideoSend() {
  videoSendTimer = null;
  if (videoSendQueue.length === 0) {
    nextVideoSendAt = 0;
    return;
  }

  flushOneVideoFrame();

  const now = performance.now();
  if (nextVideoSendAt === 0) {
    nextVideoSendAt = now + TARGET_FRAME_INTERVAL_MS;
  } else {
    nextVideoSendAt += TARGET_FRAME_INTERVAL_MS;
  }

  if (now > nextVideoSendAt + TARGET_FRAME_INTERVAL_MS * 1.5) {
    nextVideoSendAt = now + TARGET_FRAME_INTERVAL_MS;
  }

  if (videoSendQueue.length > 0) {
    scheduleVideoSend();
  }
}

function resetPacingStats() {
  pacingStats = {
    accessUnits: 0,
    bytes: 0,
    lastLogMs: Date.now(),
    lastSendMs: 0,
    maxGapMs: 0,
    minGapMs: Number.POSITIVE_INFINITY,
    gapSumMs: 0,
    gapCount: 0,
  };
}

function notePacingSend(byteLength) {
  if (!DEBUG_PACING) {
    return;
  }
  const now = Date.now();
  if (pacingStats.lastSendMs > 0) {
    const gapMs = now - pacingStats.lastSendMs;
    pacingStats.gapSumMs += gapMs;
    pacingStats.gapCount += 1;
    pacingStats.maxGapMs = Math.max(pacingStats.maxGapMs, gapMs);
    pacingStats.minGapMs = Math.min(pacingStats.minGapMs, gapMs);
  }
  pacingStats.lastSendMs = now;
  pacingStats.accessUnits += 1;
  pacingStats.bytes += byteLength;
  if (now - pacingStats.lastLogMs >= 1000) {
    const avgGapMs = pacingStats.gapCount > 0
      ? (pacingStats.gapSumMs / pacingStats.gapCount).toFixed(1)
      : "n/a";
    console.log(
      `[pacing] au/s=${pacingStats.accessUnits} ` +
        `kbps=${Math.round((pacingStats.bytes * 8) / 1000)} ` +
        `queue=${videoSendQueue.length} ` +
        `gapMs avg=${avgGapMs} min=${pacingStats.minGapMs === Number.POSITIVE_INFINITY ? "n/a" : pacingStats.minGapMs} ` +
        `max=${pacingStats.maxGapMs}`
    );
    pacingStats.accessUnits = 0;
    pacingStats.bytes = 0;
    pacingStats.lastLogMs = now;
    pacingStats.maxGapMs = 0;
    pacingStats.minGapMs = Number.POSITIVE_INFINITY;
    pacingStats.gapSumMs = 0;
    pacingStats.gapCount = 0;
  }
}

function sendBinaryImmediate(frame) {
  if (!loggedFirstFrame) {
    console.log(`First video payload sent (${frame.length} bytes)`);
    loggedFirstFrame = true;
  }
  if (!wss) {
    return;
  }
  const payload = Buffer.concat([Buffer.from([VIDEO_BINARY_TYPE]), Buffer.from(frame)]);
  notePacingSend(payload.length);
  wss.clients.forEach((client) => {
    if (client.readyState === 1) { // OPEN
      client.send(payload);
    }
  });
}

function sendBinary(frame) {
  enqueueVideoFrame(frame);
}

function broadcastText(message) {
  if (!wss) {
    return;
  }
  wss.clients.forEach((client) => {
    if (client.readyState === 1) {
      client.send(message);
    }
  });
}

function sendVideoConfig() {
  if (!lastVideoConfig || sentVideoConfig) {
    return;
  }
  console.log(
    `Sending video_config ${lastVideoConfig.width}x${lastVideoConfig.height} ` +
      `fps=${lastVideoConfig.fps} encoder=${lastVideoConfig.encoder}`
  );
  broadcastText(JSON.stringify(lastVideoConfig));
  sentVideoConfig = true;
}

function sendCaptureStatus() {
  if (!currentCaptureMethod) {
    return;
  }
  broadcastText(JSON.stringify({
    type: "capture_status",
    monitor: activeMonitorIndex + 1,
    method: currentCaptureMethod,
  }));
}

function sendStreamReset() {
  broadcastText(JSON.stringify({
    type: "stream_reset",
    monitor: activeMonitorIndex + 1,
  }));
}

function handleRawCaptureStderr(chunk) {
  rawCaptureStderrBuffer += chunk.toString("utf8");
  const lines = rawCaptureStderrBuffer.split(/\r?\n/);
  rawCaptureStderrBuffer = lines.pop() || "";

  for (const line of lines) {
    if (line.startsWith("CURSOR ")) {
      lastCursorMessage = line.slice(7);
      broadcastText(lastCursorMessage);
    } else if (line.trim()) {
      process.stderr.write(`[capture] ${line}\n`);
    }
  }
}

function buildAvcConfigRecord(sps, pps) {
  if (!sps || !pps || sps.length < 4 || pps.length < 1) {
    return null;
  }

  const config = Buffer.alloc(11 + sps.length + pps.length);
  let offset = 0;
  config[offset++] = 1;
  config[offset++] = sps[1];
  config[offset++] = sps[2];
  config[offset++] = sps[3];
  config[offset++] = 0xfc | 3;
  config[offset++] = 0xe0 | 1;
  config.writeUInt16BE(sps.length, offset);
  offset += 2;
  sps.copy(config, offset);
  offset += sps.length;
  config[offset++] = 1;
  config.writeUInt16BE(pps.length, offset);
  offset += 2;
  pps.copy(config, offset);
  offset += pps.length;
  return config.slice(0, offset);
}

function flushAccessUnit(accessUnit, isKeyframe) {
  if (!accessUnit.length) {
    return;
  }

  const chunks = [];
  if (isKeyframe && lastSps && lastPps) {
    for (const nal of [lastSps, lastPps]) {
      chunks.push(Buffer.from([0, 0, 0, 1]), Buffer.from(nal));
    }
  }

  for (const nal of accessUnit) {
    chunks.push(Buffer.from([0, 0, 0, 1]), Buffer.from(nal));
  }

  const frame = Buffer.concat(chunks);
  if (isKeyframe) {
    lastKeyframe = Buffer.concat([Buffer.from([VIDEO_BINARY_TYPE]), frame]);
  }
  sendBinary(frame);
}

function handleNalUnit(nal) {
  if (!nal || nal.length === 0) {
    return;
  }

  const nalType = nal[0] & 0x1f;
  if (nalType === 7) {
    lastSps = Buffer.from(nal);
    if (lastPps && activeMonitorInfo && !lastVideoConfig) {
      const avcConfig = buildAvcConfigRecord(lastSps, lastPps);
      lastVideoConfig = {
        type: "video_config",
        codec: "video/avc",
        width: (activeVideoInfo || activeMonitorInfo).width,
        height: (activeVideoInfo || activeMonitorInfo).height,
        fps: 60,
        encoder: currentEncoderName,
        capture_method: currentCaptureMethod,
        sps: lastSps.toString("base64"),
        pps: lastPps.toString("base64"),
        config: avcConfig ? avcConfig.toString("base64") : null,
      };
      sendVideoConfig();
    }
    return;
  }

  if (nalType === 8) {
    lastPps = Buffer.from(nal);
    if (lastSps && activeMonitorInfo && !lastVideoConfig) {
      const avcConfig = buildAvcConfigRecord(lastSps, lastPps);
      lastVideoConfig = {
        type: "video_config",
        codec: "video/avc",
        width: (activeVideoInfo || activeMonitorInfo).width,
        height: (activeVideoInfo || activeMonitorInfo).height,
        fps: 60,
        encoder: currentEncoderName,
        capture_method: currentCaptureMethod,
        sps: lastSps.toString("base64"),
        pps: lastPps.toString("base64"),
        config: avcConfig ? avcConfig.toString("base64") : null,
      };
      sendVideoConfig();
    }
    return;
  }

  if (nalType === 9) {
    if (currentAccessUnit.length > 0) {
      flushAccessUnit(currentAccessUnit, currentAccessUnitHasIdr);
      currentAccessUnit = [];
      currentAccessUnitHasIdr = false;
    }
    return;
  }

  if (nalType === 5) {
    currentAccessUnitHasIdr = true;
  }
  currentAccessUnit.push(Buffer.from(nal));
}

function parseAnnexBStream(chunk) {
  nalBuffer = Buffer.concat([nalBuffer, chunk]);

  let start = -1;
  let startLength = 0;

  for (let i = 0; i + 3 < nalBuffer.length; i++) {
    if (nalBuffer[i] !== 0 || nalBuffer[i + 1] !== 0) {
      continue;
    }
    if (nalBuffer[i + 2] === 1) {
      start = i;
      startLength = 3;
      break;
    }
    if (i + 3 < nalBuffer.length && nalBuffer[i + 2] === 0 && nalBuffer[i + 3] === 1) {
      start = i;
      startLength = 4;
      break;
    }
  }

  if (start < 0) {
    if (nalBuffer.length > 4) {
      nalBuffer = nalBuffer.slice(nalBuffer.length - 4);
    }
    return;
  }

  if (start > 0) {
    nalBuffer = nalBuffer.slice(start);
  }

  while (true) {
    let next = -1;
    let nextLength = 0;
    for (let i = startLength; i + 3 < nalBuffer.length; i++) {
      if (nalBuffer[i] !== 0 || nalBuffer[i + 1] !== 0) {
        continue;
      }
      if (nalBuffer[i + 2] === 1) {
        next = i;
        nextLength = 3;
        break;
      }
      if (i + 3 < nalBuffer.length && nalBuffer[i + 2] === 0 && nalBuffer[i + 3] === 1) {
        next = i;
        nextLength = 4;
        break;
      }
    }

    if (next < 0) {
      if (start > 0) {
        nalBuffer = nalBuffer.slice(0);
      }
      break;
    }

    const nal = nalBuffer.slice(startLength, next);
    handleNalUnit(nal);
    nalBuffer = nalBuffer.slice(next);
    startLength = nextLength;
  }
}

function getStreamSize(monitorInfo, monitorIndex) {
  const maxWidth = monitorIndex === 0 ? 2560 : 1280;
  const maxHeight = monitorIndex === 0 ? 1440 : 720;
  const scale = Math.min(maxWidth / monitorInfo.width, maxHeight / monitorInfo.height);
  if (scale >= 1) {
    return { width: monitorInfo.width, height: monitorInfo.height };
  }
  return {
    width: Math.max(1, Math.round(monitorInfo.width * scale)),
    height: Math.max(1, Math.round(monitorInfo.height * scale)),
  };
}

function getCaptureDescription(monitorInfo, monitorIndex) {
  return "raw Capture.exe pipe";
}

function getVideoBitrateKbps(videoInfo) {
  const pixelsPerFrame = Math.max(1, videoInfo.width * videoInfo.height);
  const target = Math.round((pixelsPerFrame * 60 * 0.12) / 1000);
  return Math.max(8000, Math.min(35000, target));
}

function buildRawFfmpegArgs(encoderName, videoInfo) {
  const bitrateKbps = getVideoBitrateKbps(videoInfo);
  const common = [
    "-hide_banner",
    "-loglevel",
    "error",
    "-f",
    "rawvideo",
    "-pix_fmt",
    "bgr0",
    "-video_size",
    `${videoInfo.width}x${videoInfo.height}`,
    "-framerate",
    "60",
    "-i",
    "pipe:0",
    "-an",
    "-vsync",
    "cfr",
  ];

  if (encoderName === "h264_nvenc") {
    return common.concat([
      "-vf",
      `scale=${videoInfo.width}:${videoInfo.height}:flags=fast_bilinear,format=nv12`,
      "-c:v",
      "h264_nvenc",
      "-preset",
      "p1",
      "-tune",
      "ll",
      "-rc",
      "cbr",
      "-b:v",
      `${bitrateKbps}k`,
      "-maxrate",
      `${bitrateKbps}k`,
      "-bufsize",
      `${Math.max(1000, Math.round(bitrateKbps / 2))}k`,
      "-g",
      "60",
      "-keyint_min",
      "60",
      "-bf",
      "0",
      "-zerolatency",
      "1",
      "-rc-lookahead",
      "0",
      "-aud",
      "1",
      "-bsf:v",
      "dump_extra,h264_metadata=aud=insert",
      "-f",
      "h264",
      "pipe:1",
    ]);
  }

  return common.concat([
    "-c:v",
    encoderName,
    "-vf",
    "format=nv12",
    "-profile:v",
    "baseline",
    "-preset",
    "p5",
    "-tune",
    "ll",
    "-rc",
    "cbr",
    "-b:v",
    `${bitrateKbps}k`,
    "-maxrate",
    `${bitrateKbps}k`,
    "-bufsize",
    `${Math.max(1000, Math.round(bitrateKbps / 2))}k`,
    "-g",
    "60",
    "-keyint_min",
    "60",
    "-bf",
    "0",
    "-zerolatency",
    "1",
    "-rc-lookahead",
    "0",
    "-aud",
    "1",
    "-bsf:v",
    "dump_extra,h264_metadata=aud=insert",
    "-f",
    "h264",
    "pipe:1",
  ]);
}

function buildDirectFfmpegArgs(probeInfo, videoInfo) {
  const bitrateKbps = getVideoBitrateKbps(videoInfo);
  const captureInput = [
    `ddagrab=output_idx=${probeInfo.outputIndex}`,
    `framerate=60`,
    `video_size=${probeInfo.width}x${probeInfo.height}`,
    "offset_x=0",
    "offset_y=0",
    "draw_mouse=0",
    "output_fmt=bgra",
    "allow_fallback=1",
  ].join(":");

  const needsScale =
    videoInfo.width !== probeInfo.width ||
    videoInfo.height !== probeInfo.height;

  const args = [
    "-hide_banner",
    "-loglevel",
    "error",
    "-init_hw_device",
    `d3d11va=dxgi:${probeInfo.adapterIndex}`,
    "-filter_hw_device",
    "dxgi",
    "-f",
    "lavfi",
    "-i",
    captureInput,
    "-an",
  ];

  if (needsScale) {
    args.push(
      "-vf",
      `hwdownload,format=bgra,scale=${videoInfo.width}:${videoInfo.height}:flags=fast_bilinear,format=nv12`
    );
  }

  return args.concat([
    "-c:v",
    "h264_nvenc",
    "-preset",
    "p1",
    "-tune",
    "ll",
    "-rc",
    "cbr",
    "-b:v",
    `${bitrateKbps}k`,
    "-maxrate",
    `${bitrateKbps}k`,
    "-bufsize",
    `${Math.max(1000, Math.round(bitrateKbps / 2))}k`,
    "-g",
    "60",
    "-keyint_min",
    "60",
    "-bf",
    "0",
    "-zerolatency",
    "1",
    "-rc-lookahead",
    "0",
    "-aud",
    "1",
    "-r",
    "60",
    "-vsync",
    "cfr",
    "-bsf:v",
    "dump_extra,h264_metadata=aud=insert",
    "-f",
    "h264",
    "pipe:1",
  ]);
}

let currentEncoderName = captureEncoders[0];

function startRawCapturePipeline() {
  if (shuttingDown) {
    return;
  }

  if (!ffmpegPath) {
    throw new Error("No ffmpeg runtime found");
  }

  if (!activeMonitorInfo) {
    activeMonitorInfo = getMonitorInfo(activeMonitorIndex);
  }
  stopCursorCaptureProcess();
  stopRawCaptureProcess();
  activeVideoInfo = getStreamSize(activeMonitorInfo, activeMonitorIndex);

  resetStreamState();
  rawCaptureStderrBuffer = "";
  currentCaptureMethod = "DXGI duplicate output";
  const captureExe = path.join(__dirname, "Capture.exe");
  const captureArgs = ["--raw", "--monitor", String(activeMonitorIndex + 1), "--stream-size", `${activeVideoInfo.width}x${activeVideoInfo.height}`];
  const ffmpegArgs = buildRawFfmpegArgs(currentEncoderName, activeVideoInfo);
  console.log(
    `Starting raw capture + ${currentEncoderName} on monitor ${activeMonitorIndex + 1} ` +
      `(${getCaptureDescription(activeMonitorInfo, activeMonitorIndex)}) ` +
      `${activeMonitorInfo.deviceName || ""} ` +
      `(${activeMonitorInfo.width}x${activeMonitorInfo.height} -> ${activeVideoInfo.width}x${activeVideoInfo.height})`
  );
  sendCaptureStatus();
  rawCaptureProcess = spawn(captureExe, captureArgs, { stdio: ["ignore", "pipe", "pipe"] });
  captureProcess = spawn(ffmpegPath, ffmpegArgs, { stdio: ["pipe", "pipe", "pipe"] });
  rawCaptureProcess.stdout.pipe(captureProcess.stdin);
  captureProcess.stdin.on("error", (err) => {
    if (err && err.code !== "EPIPE" && err.code !== "EOF") {
      console.error(`ffmpeg stdin error: ${err.message || err}`);
    }
  });
  rawCaptureProcess.stderr.on("data", (chunk) => {
    handleRawCaptureStderr(chunk);
  });
  rawCaptureProcess.on("exit", (code, signal) => {
    if (shuttingDown || restartRequested) {
      return;
    }
    console.error(`raw capture exited (code=${code}, signal=${signal})`);
    if (captureProcess && !captureProcess.killed) {
      captureProcess.kill();
    }
  });

  captureProcess.stdout.on("data", (chunk) => {
    parseAnnexBStream(chunk);
  });

  captureProcess.stderr.on("data", (chunk) => {
    process.stderr.write(`[ffmpeg] ${chunk.toString("utf8")}`);
  });

  captureProcess.on("exit", (code, signal) => {
    if (shuttingDown) {
      return;
    }

    console.error(`raw h264 capture exited (code=${code}, signal=${signal})`);
    try {
      rawCaptureProcess?.stdout?.unpipe(captureProcess.stdin);
    } catch {}
    stopRawCaptureProcess();
    if (restartRequested) {
      restartRequested = false;
      setTimeout(startCaptureProcess, 300);
      return;
    }
    const encoderIndex = captureEncoders.indexOf(currentEncoderName);
    if (code !== 0 && encoderIndex >= 0 && encoderIndex + 1 < captureEncoders.length) {
      currentEncoderName = captureEncoders[encoderIndex + 1];
      setTimeout(startCaptureProcess, 500);
      return;
    }

    setTimeout(startCaptureProcess, 1000);
  });
}

function startDirectCapturePipeline() {
  if (shuttingDown) {
    return;
  }

  if (!ffmpegPath) {
    throw new Error("No ffmpeg runtime found");
  }

  if (!activeMonitorInfo) {
    activeMonitorInfo = getMonitorInfo(activeMonitorIndex);
  }

  stopRawCaptureProcess();
  stopCursorCaptureProcess();
  activeVideoInfo = getStreamSize(activeMonitorInfo, activeMonitorIndex);

  let probeInfo;
  try {
    probeInfo = probeMonitorForDirectCapture(activeMonitorIndex);
  } catch (err) {
    console.error(`Direct pipeline probe failed, falling back to raw capture: ${err.message || err}`);
    directCaptureAllowed = false;
    startRawCapturePipeline();
    return;
  }

  resetStreamState();
  rawCaptureStderrBuffer = "";
  currentCaptureMethod = "Direct D3D11 duplicate output -> NVENC";
  const captureExe = path.join(__dirname, "Capture.exe");
  activeVideoInfo = getStreamSize(activeMonitorInfo, activeMonitorIndex);
  const cursorArgs = ["--cursor-only", "--monitor", String(activeMonitorIndex + 1), "--stream-size", `${activeVideoInfo.width}x${activeVideoInfo.height}`];
  const ffmpegArgs = buildDirectFfmpegArgs(probeInfo, activeVideoInfo);
  console.log(
    `Starting direct GPU capture + ${currentEncoderName} on monitor ${activeMonitorIndex + 1} ` +
      `(${probeInfo.deviceName || activeMonitorInfo.deviceName || ""}) ` +
      `adapter ${probeInfo.adapterIndex} output ${probeInfo.outputIndex} ` +
      `(${activeMonitorInfo.width}x${activeMonitorInfo.height} -> ${activeVideoInfo.width}x${activeVideoInfo.height})`
  );
  sendCaptureStatus();
  cursorCaptureProcess = spawn(captureExe, cursorArgs, { stdio: ["ignore", "ignore", "pipe"] });
  captureProcess = spawn(ffmpegPath, ffmpegArgs, { stdio: ["ignore", "pipe", "pipe"] });

  cursorCaptureProcess.stderr.on("data", (chunk) => {
    handleRawCaptureStderr(chunk);
  });

  captureProcess.stdout.on("data", (chunk) => {
    parseAnnexBStream(chunk);
  });

  captureProcess.stderr.on("data", (chunk) => {
    process.stderr.write(`[ffmpeg] ${chunk.toString("utf8")}`);
  });

  cursorCaptureProcess.on("exit", (code, signal) => {
    if (shuttingDown || restartRequested) {
      return;
    }
    console.error(`cursor metadata process exited (code=${code}, signal=${signal})`);
  });

  captureProcess.on("exit", (code, signal) => {
    if (shuttingDown) {
      return;
    }

    console.error(`direct h264 capture exited (code=${code}, signal=${signal})`);
    stopCursorCaptureProcess();
    if (restartRequested) {
      restartRequested = false;
      setTimeout(startCaptureProcess, 300);
      return;
    }

    if (directCaptureAllowed) {
      directCaptureAllowed = false;
      console.error("Direct pipeline failed, switching to raw fallback");
      setTimeout(startCaptureProcess, 300);
      return;
    }

    setTimeout(startCaptureProcess, 1000);
  });
}

function startCaptureProcess() {
  if (directCaptureAllowed) {
    startDirectCapturePipeline();
    return;
  }
  startRawCapturePipeline();
}

function buildAudioFfmpegArgs(device) {
  if (!device) {
    throw new Error(
      "No DirectShow audio capture device found. Install a loopback device like virtual-audio-capturer."
    );
  }
  return [
    "-hide_banner",
    "-loglevel",
    "error",
    "-thread_queue_size",
    "512",
    "-f",
    "dshow",
    "-i",
    `audio=${device}`,
    "-ac",
    String(AUDIO_CHANNELS),
    "-ar",
    String(AUDIO_SAMPLE_RATE),
    "-acodec",
    "pcm_s16le",
    "-f",
    "s16le",
    "pipe:1",
  ];
}

function startAudioCapturePipeline() {
  if (shuttingDown) {
    return;
  }

  if (!ffmpegPath) {
    throw new Error("No ffmpeg runtime found");
  }

  stopAudioCaptureProcess();
  resetAudioStreamState();
  lastAudioConfig = {
    type: "audio_config",
    codec: "audio/pcm",
    sample_rate: AUDIO_SAMPLE_RATE,
    channels: AUDIO_CHANNELS,
    sample_format: "s16le",
    frame_duration_ms: AUDIO_FRAME_DURATION_MS,
  };
  sentAudioConfig = false;
  sendAudioStatus("starting", "Desktop audio capture starting");
  sendAudioConfig();

  const devices = AUDIO_DEVICE_CANDIDATES.length ? AUDIO_DEVICE_CANDIDATES : [null];

  const tryStart = (index) => {
    if (shuttingDown) {
      return;
    }

    audioDeviceIndex = index;
    const device = devices[index];
    if (!device) {
      console.error("Desktop audio unavailable: no DirectShow audio capture devices remain");
      sendAudioStatus("unavailable", "Desktop audio unavailable");
      return;
    }

    let audioArgs;
    try {
      audioArgs = buildAudioFfmpegArgs(device);
    } catch (err) {
      console.error(`Desktop audio unavailable: ${err.message || err}`);
      sendAudioStatus("unavailable", err.message || "Desktop audio unavailable");
      return;
    }

    audioDeviceName = device;
    console.log(`Starting desktop audio capture on DirectShow device: ${device}`);
    audioCaptureProcess = spawn(ffmpegPath, audioArgs, { stdio: ["ignore", "pipe", "pipe"] });
    sendAudioStatus("active", `Desktop audio connected (${device})`);

    audioCaptureProcess.stdout.on("data", (chunk) => {
      emitAudioPacket(chunk);
    });

    let stderrBuffer = "";
    audioCaptureProcess.stderr.on("data", (chunk) => {
      const text = chunk.toString("utf8");
      stderrBuffer += text;
      process.stderr.write(`[audio] ${text}`);
    });

    audioCaptureProcess.on("exit", (code, signal) => {
      if (shuttingDown) {
        return;
      }

      if (code === 0) {
        console.log("Desktop audio capture ended cleanly");
        sendAudioStatus("stopped", "Desktop audio ended");
        return;
      }

      console.error(`desktop audio capture exited (code=${code}, signal=${signal}) on ${device}`);
      const nextIndex = index + 1;
      if (nextIndex < devices.length) {
        stopAudioCaptureProcess();
        setTimeout(() => tryStart(nextIndex), 300);
        return;
      }

      sendAudioStatus(
        "unavailable",
        stderrBuffer.trim() || `Desktop audio capture exited (code=${code}, signal=${signal})`
      );
      stopAudioCaptureProcess();
    });
  };

  tryStart(0);
}

ffmpegPath = findExecutable(ffmpegCandidates);
if (!ffmpegPath) {
  throw new Error("No ffmpeg runtime found");
}

startCaptureProcess();
startAudioCapturePipeline();

function startInputProcess() {
  if (shuttingDown) return;
  const captureExe = path.join(__dirname, "Capture.exe");
  if (!fs.existsSync(captureExe)) {
    console.error(`Input handler error: ${captureExe} not found. Please build Capture.exe`);
    return;
  }
  console.log("Starting input handler process");
  try {
    inputProcess = spawn(captureExe, ["--input"], { stdio: ["pipe", "ignore", "pipe"] });
    inputProcess.on("error", (err) => {
      console.error("Failed to start input process:", err);
    });
    inputProcess.stderr.on("data", (data) => {
      process.stderr.write(`[input] ${data}`);
    });
    inputProcess.on("exit", (code) => {
      if (!shuttingDown) {
        console.log(`Input handler exited (code ${code}), restarting...`);
        setTimeout(startInputProcess, 2000);
      }
    });
  } catch (err) {
    console.error("Error spawning input process:", err);
  }
}

function stopInputProcess() {
  if (inputProcess && !inputProcess.killed) {
    inputProcess.kill();
  }
  inputProcess = null;
}

startInputProcess();

// --- WebSocket Server ---
const server = http.createServer();
wss = new WebSocketServer({ server, perMessageDeflate: false });

wss.on("connection", (ws) => {
  if (ws._socket?.setNoDelay) {
    ws._socket.setNoDelay(true);
  }
  console.log("Client connected");
  if (lastVideoConfig) {
    ws.send(JSON.stringify(lastVideoConfig));
  }
  if (lastAudioConfig) {
    ws.send(JSON.stringify(lastAudioConfig));
  }
  if (lastAudioStatus) {
    ws.send(JSON.stringify(lastAudioStatus));
  }
  if (lastKeyframe) {
    ws.send(lastKeyframe);
  }
  if (lastCursorMessage) {
    ws.send(lastCursorMessage);
  }

  ws.on("message", (message) => {
    try {
      const data = JSON.parse(message.toString());
      if (data.type === "set_monitor") {
        const nextMonitor = Math.max(0, Number(data.value) - 1);
        if (Number.isInteger(nextMonitor) && nextMonitor !== activeMonitorIndex) {
          activeMonitorIndex = nextMonitor;
          activeMonitorInfo = getMonitorInfo(activeMonitorIndex);
          currentEncoderName = captureEncoders[0];
          directCaptureAllowed = true;
          sendStreamReset();
          restartRequested = true;
          stopRawCaptureProcess();
          stopCursorCaptureProcess();
          if (captureProcess && !captureProcess.killed) {
            captureProcess.kill();
          } else {
            restartRequested = false;
            startCaptureProcess();
          }
          console.log(`Switched to monitor ${data.value}`);
        }
      } else if (data.type === "mouse_move") {
        if (inputProcess && !inputProcess.killed) {
          inputProcess.stdin.write(`mr ${Math.round(data.dx)} ${Math.round(data.dy)}\n`);
        }
      } else if (data.type === "mouse_move_abs") {
        if (inputProcess && !inputProcess.killed) {
          const monitor = activeMonitorInfo || { x: 0, y: 0 };
          const absX = Math.round(data.x) + monitor.x;
          const absY = Math.round(data.y) + monitor.y;
          inputProcess.stdin.write(`mm ${absX} ${absY}\n`);
        }
      } else if (data.type === "mouse_down") {
        inputProcess?.stdin.write(`md ${data.button}\n`);
      } else if (data.type === "mouse_up") {
        inputProcess?.stdin.write(`mu ${data.button}\n`);
      } else if (data.type === "mouse_wheel") {
        inputProcess?.stdin.write(`mw ${Math.round(data.delta)}\n`);
      } else if (data.type === "key_down") {
        if (data.shift) inputProcess?.stdin.write(`kd 16\n`);
        inputProcess?.stdin.write(`kd ${data.vk}\n`);
      } else if (data.type === "key_up") {
        inputProcess?.stdin.write(`ku ${data.vk}\n`);
        if (data.shift) inputProcess?.stdin.write(`ku 16\n`);
      }
    } catch (e) {}
  });

  ws.on("close", () => {
    console.log("Client disconnected");
  });
});

server.listen(WS_PORT);

function shutdown() {
  shuttingDown = true;
  stopCaptureProcess();
  stopAudioCaptureProcess();
  stopInputProcess();
  process.exit(0);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
process.on("exit", () => {
  stopCaptureProcess();
  stopAudioCaptureProcess();
});
