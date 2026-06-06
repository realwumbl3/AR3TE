const dgram = require("dgram");
const os = require("os");
const http = require("http");
const fs = require("fs");
const { spawn, execSync } = require("child_process");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = 45678;
const WS_PORT = 45679;
const DISCOVER_MSG = "RDESK_DISCOVER";

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

function killExistingInstances() {
  const pids = new Set([...getPidsOnPort(PORT), ...getPidsOnPort(WS_PORT)]);

  for (const pid of pids) {
    killPid(pid);
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

  if (pids.size > 0) {
    console.log(`Stopped ${pids.size} existing server instance(s)`);
    const deadline = Date.now() + 500;
    while (Date.now() < deadline) {
      // Allow the OS to release ports before rebinding
    }
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
const socket = dgram.createSocket("udp4");

socket.on("message", (message, remote) => {
  if (message.toString() !== DISCOVER_MSG) {
    return;
  }

  const payload = JSON.stringify({
    type: "rdesk",
    name: machineName,
    host: localIp,
    port: PORT,
    wsPort: WS_PORT,
  });

  socket.send(payload, remote.port, remote.address);
});

socket.on("error", (error) => {
  if (error.code === "EADDRINUSE") {
    console.error(`Port ${PORT} is already in use.`);
    process.exit(1);
  }
  console.error(error);
  process.exit(1);
});

socket.on("listening", () => {
  socket.setBroadcast(true);
  console.log("Remote desktop discovery server running");
  console.log(`  Machine: ${machineName}`);
  console.log(`  UDP Discovery: ${localIp}:${PORT}`);
  console.log(`  WebSocket Stream: ws://${localIp}:${WS_PORT}`);
});

socket.bind(PORT);

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
let lastCursorMessage = null;

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
}

function stopRawCaptureProcess() {
  if (rawCaptureProcess && !rawCaptureProcess.killed) {
    rawCaptureProcess.kill();
  }
  rawCaptureProcess = null;
}

function stopCaptureProcess() {
  stopRawCaptureProcess();
  if (captureProcess && !captureProcess.killed) {
    captureProcess.kill();
  }
}

function sendBinary(frame) {
  if (!loggedFirstFrame) {
    console.log(`First video payload sent (${frame.length} bytes)`);
    loggedFirstFrame = true;
  }
  wss.clients.forEach((client) => {
    if (client.readyState === 1) { // OPEN
      client.send(frame);
    }
  });
}

function broadcastText(message) {
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
    lastKeyframe = frame;
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

function getStreamSize(monitorInfo) {
  const maxWidth = 2560;
  const maxHeight = 1440;
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

function buildRawFfmpegArgs(encoderName, videoInfo) {
  const common = [
    "-hide_banner",
    "-loglevel",
    "error",
    "-f",
    "rawvideo",
    "-pix_fmt",
    "bgra",
    "-video_size",
    `${videoInfo.width}x${videoInfo.height}`,
    "-framerate",
    "60",
    "-i",
    "pipe:0",
    "-an",
    "-vf",
    "format=nv12",
  ];

  return common.concat([
    "-c:v",
    encoderName,
    "-profile:v",
    "baseline",
    "-preset",
    "p5",
    "-tune",
    "ll",
    "-rc",
    "vbr",
    "-cq",
    "19",
    "-b:v",
    "0",
    "-g",
    "30",
    "-bf",
    "0",
    "-zerolatency",
    "1",
    "-aud",
    "1",
    "-bsf:v",
    "dump_extra,h264_metadata=aud=insert",
    "-f",
    "h264",
    "pipe:1",
  ]);
}

let currentEncoderName = captureEncoders[0];

function startCaptureProcess() {
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
  activeVideoInfo = getStreamSize(activeMonitorInfo);

  resetStreamState();
  rawCaptureStderrBuffer = "";
  const captureExe = path.join(__dirname, "Capture.exe");
  const captureArgs = ["--raw", "--monitor", String(activeMonitorIndex + 1)];
  const ffmpegArgs = buildRawFfmpegArgs(currentEncoderName, activeVideoInfo);
  console.log(
    `Starting raw capture + ${currentEncoderName} on monitor ${activeMonitorIndex + 1} ` +
      `(${getCaptureDescription(activeMonitorInfo, activeMonitorIndex)}) ` +
      `${activeMonitorInfo.deviceName || ""} ` +
      `(${activeMonitorInfo.width}x${activeMonitorInfo.height} -> ${activeVideoInfo.width}x${activeVideoInfo.height})`
  );
  rawCaptureProcess = spawn(captureExe, captureArgs, { stdio: ["ignore", "pipe", "pipe"] });
  captureProcess = spawn(ffmpegPath, ffmpegArgs, { stdio: ["pipe", "pipe", "pipe"] });
  rawCaptureProcess.stdout.pipe(captureProcess.stdin);
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

    console.error(`h264 capture exited (code=${code}, signal=${signal})`);
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

ffmpegPath = findExecutable(ffmpegCandidates);
if (!ffmpegPath) {
  throw new Error("No ffmpeg runtime found");
}

startCaptureProcess();

// --- WebSocket Server ---
const server = http.createServer();
const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
  console.log("Client connected");
  if (lastVideoConfig) {
    ws.send(JSON.stringify(lastVideoConfig));
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
          sendStreamReset();
          restartRequested = true;
          stopRawCaptureProcess();
          if (captureProcess && !captureProcess.killed) {
            captureProcess.kill();
          } else {
            restartRequested = false;
            startCaptureProcess();
          }
          console.log(`Switched to monitor ${data.value}`);
        }
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
  process.exit(0);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
process.on("exit", () => {
  stopCaptureProcess();
});
