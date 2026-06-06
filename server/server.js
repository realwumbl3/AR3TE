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

// --- High Performance Capture Process ---
const captureDllCandidates = [
  path.join(__dirname, "bin", "Release", "net8.0-windows", "Capture.dll"),
  path.join(__dirname, "Capture.dll"),
  path.join("C:\\tmp", "capture-build", "Capture.dll"),
];
const captureDll = captureDllCandidates.find((candidate) => fs.existsSync(candidate));
const captureExeCandidates = [
  path.join(__dirname, "bin", "Release", "net8.0-windows", "Capture.exe"),
  path.join(__dirname, "Capture.exe"),
];
const captureExe = captureExeCandidates.find((candidate) => fs.existsSync(candidate));
const captureCommand = captureDll ? "dotnet" : captureExe;
const captureArgs = captureDll ? [captureDll] : [];
const MAX_FRAME_BYTES = 20 * 1024 * 1024;

function describeCaptureArtifact() {
  const artifactPath = captureDll || captureExe;
  if (!artifactPath) {
    return null;
  }

  const kind = captureDll ? "DLL" : "EXE";
  let builtAt = "unknown";
  try {
    builtAt = fs.statSync(artifactPath).mtime.toISOString();
  } catch {
    // Ignore stat failures and keep the path in the log.
  }

  return { artifactPath, kind, builtAt };
}

let captureProcess = null;
let shuttingDown = false;
let lastFrame = null;
let buffer = Buffer.alloc(0);
let stderrBuffer = "";

function startCaptureProcess() {
  if (shuttingDown) {
    return;
  }
  if (!captureCommand) {
    throw new Error("No capture runtime found");
  }
  const captureArtifact = describeCaptureArtifact();
  if (captureArtifact) {
    console.log(
      `Starting capture from ${captureArtifact.kind}: ${captureArtifact.artifactPath} (mtime ${captureArtifact.builtAt})`
    );
  }
  captureProcess = spawn(captureCommand, captureArgs, { stdio: ["pipe", "pipe", "pipe"] });

  captureProcess.stdout.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);

    while (buffer.length >= 4) {
      const length = buffer.readInt32LE(0);
      if (length <= 0 || length > MAX_FRAME_BYTES) {
        console.error(`Invalid frame length (${length}), resetting stream parser`);
        buffer = Buffer.alloc(0);
        break;
      }
      if (buffer.length >= 4 + length) {
        lastFrame = buffer.slice(4, 4 + length);
        broadcastFrame(lastFrame);
        buffer = buffer.slice(4 + length);
      } else {
        break;
      }
    }
  });

  captureProcess.stderr.on("data", (chunk) => {
    stderrBuffer += chunk.toString("utf8");
    let newlineIndex;
    while ((newlineIndex = stderrBuffer.indexOf("\n")) !== -1) {
      const line = stderrBuffer.slice(0, newlineIndex).replace(/\r$/, "");
      stderrBuffer = stderrBuffer.slice(newlineIndex + 1);
      if (line.startsWith("CURSOR ")) {
        const payload = line.slice(7);
        try {
          const cursor = JSON.parse(payload);
          broadcastText(JSON.stringify({ type: "cursor", ...cursor }));
        } catch {
          process.stderr.write(`[Capture] ${line}\n`);
        }
      } else if (line.length > 0) {
        process.stderr.write(`[Capture] ${line}\n`);
      }
    }
  });

  captureProcess.on("exit", (code, signal) => {
    if (shuttingDown) {
      return;
    }
    console.error(`Capture process exited (code=${code}, signal=${signal}), restarting...`);
    setTimeout(startCaptureProcess, 1000);
  });
}

startCaptureProcess();

function broadcastFrame(frame) {
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

// --- WebSocket Server ---
const server = http.createServer();
const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
  console.log("Client connected");
  if (lastFrame) {
    ws.send(lastFrame);
  }

  ws.on("message", (message) => {
    try {
      const data = JSON.parse(message.toString());
      if (data.type === "set_monitor") {
        if (captureProcess && !captureProcess.killed) {
          captureProcess.stdin.write(`MONITOR ${data.value}\n`);
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
  if (captureProcess && !captureProcess.killed) {
    captureProcess.kill();
  }
  process.exit(0);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
process.on("exit", () => {
  if (captureProcess && !captureProcess.killed) {
    captureProcess.kill();
  }
});
