@echo off
setlocal

cd /d "%~dp0server"
if errorlevel 1 (
  echo Failed to change to server directory.
  pause
  exit /b 1
)

where npm >nul 2>&1
if errorlevel 1 (
  echo npm not found. Install Node.js and ensure it is on PATH.
  pause
  exit /b 1
)

if not exist "node_modules\" (
  echo Installing server dependencies...
  call npm install
  if errorlevel 1 (
    echo npm install failed.
    pause
    exit /b 1
  )
)

echo Starting AR3TE host server...
call npm run dev

if errorlevel 1 (
  echo Server exited with an error.
  pause
  exit /b 1
)

endlocal
