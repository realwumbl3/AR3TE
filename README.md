# r.3

Remote desktop viewer for Android. Stream a Windows PC screen to your phone over the local network with automatic machine discovery.

## Overview

**r.3** pairs an Android client with a Windows host server:

- **Android app** — discovers PCs on the LAN and displays the live remote screen over WebSocket
- **Host server** (`server/`) — UDP discovery, screen capture, and WebSocket streaming

## Requirements

### Android client

- Android Studio
- Android device or emulator (API 24+)

### Windows host

- Node.js 18+
- .NET SDK (to build `Capture.exe` from `server/Capture.cs`)
- Windows 10/11

## Getting started

### 1. Run the host server (Windows PC)

```bash
cd server
npm install
npm run dev
```

The server listens on:

| Service    | Port  |
|------------|-------|
| UDP discovery | 45678 |
| WebSocket stream | 45679 |

On first run, build the capture helper if needed:

```bash
dotnet build Capture.csproj -c Release
```

### 2. Run the Android app

1. Open the project in Android Studio
2. Build and run on a device connected to the same network as the PC
3. Grant notification permission when prompted (used for the discovery service)
4. Select a discovered machine to view its screen

## Project structure

```
├── app/          Android client (Kotlin, Jetpack Compose)
└── server/       Windows host
    ├── server.js     Discovery + WebSocket relay
    ├── Capture.cs    DXGI screen capture
    └── package.json
```

## How it works

1. The Android app broadcasts `RDESK_DISCOVER` over UDP
2. The host server responds with machine name, IP, and ports
3. The app connects via WebSocket and receives JPEG frames from `Capture.exe`
4. Monitor selection is sent back to the host as a JSON control message

## License

Private project.
