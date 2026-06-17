using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Diagnostics;
using System.Threading;
using System.Windows.Forms;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using MapFlags = Vortice.Direct3D11.MapFlags;
using DrawingSize = System.Drawing.Size;

namespace RemoteDesktop {
    public class Capture {
        private static volatile int monitorIndex = 0;
        private static volatile bool running = true;
        private static volatile bool cursorOnly = false;
        private static bool dpiAwarenessEnabled = false;
        private static int streamMaxWidth = 2560;
        private static int streamMaxHeight = 1440;
        private static IntPtr lastCursorHandle = IntPtr.Zero;
        private static string lastCursorBase64 = "";
        private static int lastCursorHotX = 0;
        private static int lastCursorHotY = 0;
        private static string lastCursorMetadataJson = "";

        private sealed class MonitorProbeInfo {
            public int AdapterIndex;
            public int OutputIndex;
            public string DeviceName;
            public Rectangle Bounds;
        }

        private const int TARGET_FPS = 60;
        private const int FRAME_INTERVAL_MS = 1000 / TARGET_FPS;
        private const int DXGI_FRAME_TIMEOUT_MS = 1;

        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [DllImport("shcore.dll")]
        private static extern int SetProcessDpiAwareness(int awareness);

        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromRect(ref RECT lprc, uint dwFlags);

        [DllImport("shcore.dll")]
        private static extern int GetDpiForMonitor(IntPtr hmonitor, int dpiType, out uint dpiX, out uint dpiY);

        [DllImport("user32.dll")]
        private static extern bool GetCursorInfo(ref CURSORINFO cursorInfo);

        [DllImport("user32.dll")]
        private static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO iconInfo);

        [DllImport("user32.dll")]
        private static extern bool GetCursorPos(out POINT point);

        [DllImport("user32.dll")]
        private static extern bool SetCursorPos(int X, int Y);

        [DllImport("user32.dll")]
        private static extern IntPtr GetCursor();

        [DllImport("user32.dll")]
        private static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr hIcon, int cx, int cy, int frame, IntPtr flicker, int flags);

        [DllImport("gdi32.dll")]
        private static extern bool DeleteObject(IntPtr hObject);

        [DllImport("user32.dll")]
        private static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, int dwExtraInfo);

        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);

        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern IntPtr GetWindow(IntPtr hWnd, uint uCmd);

        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll")]
        private static extern int GetWindowTextLength(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool IsIconic(IntPtr hWnd);

        private const int GWL_STYLE = -16;
        private const int GWL_EXSTYLE = -20;
        private const int WS_CHILD = unchecked((int)0x40000000);
        private const uint WS_EX_TOOLWINDOW = 0x00000080;
        private const uint WS_EX_APPWINDOW = 0x00040000;
        private const uint GW_OWNER = 4;

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT {
            public int Left, Top, Right, Bottom;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct POINT {
            public int X, Y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct CURSORINFO {
            public int cbSize;
            public int flags;
            public IntPtr hCursor;
            public POINT ptScreenPos;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ICONINFO {
            public bool fIcon;
            public int xHotspot;
            public int yHotspot;
            public IntPtr hbmMask;
            public IntPtr hbmColor;
        }

        private const int CURSOR_SHOWING = 0x00000001;
        private const int DI_NORMAL = 0x0003;

        private const int PROCESS_PER_MONITOR_DPI_AWARE = 2;
        private const int MDT_EFFECTIVE_DPI = 0;
        private const uint MONITOR_DEFAULTTONEAREST = 2;

        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;

        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;

        private static void EnableDpiAwareness() {
            try {
                dpiAwarenessEnabled = SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE) == 0;
                if (!dpiAwarenessEnabled) {
                    dpiAwarenessEnabled = SetProcessDPIAware();
                }
            } catch {
                try { dpiAwarenessEnabled = SetProcessDPIAware(); } catch { }
            }
        }

        private static float GetScaleFactor(Screen screen) {
            try {
                var bounds = screen.Bounds;
                var rect = new RECT {
                    Left = bounds.Left,
                    Top = bounds.Top,
                    Right = bounds.Right,
                    Bottom = bounds.Bottom
                };
                IntPtr hMonitor = MonitorFromRect(ref rect, MONITOR_DEFAULTTONEAREST);
                uint dpiX, dpiY;
                if (GetDpiForMonitor(hMonitor, MDT_EFFECTIVE_DPI, out dpiX, out dpiY) == 0) {
                    return dpiX / 96f;
                }
            } catch { }

            using (Graphics g = Graphics.FromHwnd(IntPtr.Zero)) {
                return g.DpiX / 96f;
            }
        }

        private static DrawingSize GetStreamSize(int captureWidth, int captureHeight) {
            double scale = Math.Min(
                (double)streamMaxWidth / captureWidth,
                (double)streamMaxHeight / captureHeight);
            if (scale >= 1.0) {
                return new DrawingSize(captureWidth, captureHeight);
            }
            return new DrawingSize(
                Math.Max(1, (int)Math.Round(captureWidth * scale)),
                Math.Max(1, (int)Math.Round(captureHeight * scale)));
        }

        private static Screen[] GetOrderedScreens() {
            var screens = Screen.AllScreens;
            Array.Sort(screens, (a, b) => {
                if (a.Primary != b.Primary) {
                    return a.Primary ? -1 : 1;
                }
                int xCompare = a.Bounds.X.CompareTo(b.Bounds.X);
                if (xCompare != 0) return xCompare;
                return a.Bounds.Y.CompareTo(b.Bounds.Y);
            });
            return screens;
        }

        private static string JsonEscape(string value) {
            if (value == null) {
                return "";
            }
            return value.Replace("\\", "\\\\").Replace("\"", "\\\"");
        }

        private static MonitorProbeInfo ResolveMonitorProbeInfo(int monitorIndex) {
            var screens = GetOrderedScreens();
            if (monitorIndex < 0 || monitorIndex >= screens.Length) monitorIndex = 0;
            var target = screens[monitorIndex].Bounds;

            var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
            try {
                for (uint i = 0; ; i++) {
                    if (factory.EnumAdapters1(i, out var adp).Failure) break;
                    bool keepAdapter = false;
                    try {
                        for (uint j = 0; ; j++) {
                            if (adp.EnumOutputs(j, out var outBase).Failure) break;
                            using (outBase) {
                                var out1 = outBase.QueryInterface<IDXGIOutput1>();
                                using (out1) {
                                    var rect = out1.Description.DesktopCoordinates;
                                    if (rect.Left == target.X && rect.Top == target.Y &&
                                        (rect.Right - rect.Left) == target.Width &&
                                        (rect.Bottom - rect.Top) == target.Height) {
                                        return new MonitorProbeInfo {
                                            AdapterIndex = (int)i,
                                            OutputIndex = (int)j,
                                            DeviceName = out1.Description.DeviceName,
                                            Bounds = target
                                        };
                                    }
                                }
                            }
                        }
                    } finally {
                        adp.Dispose();
                    }
                }
            } finally {
                factory.Dispose();
            }

            return new MonitorProbeInfo {
                AdapterIndex = 0,
                OutputIndex = monitorIndex,
                DeviceName = screens[monitorIndex].DeviceName,
                Bounds = target
            };
        }

        private static bool IsAltTabWindow(IntPtr hWnd) {
            if (!IsWindowVisible(hWnd) && !IsIconic(hWnd)) {
                return false;
            }

            int style = GetWindowLong(hWnd, GWL_STYLE);
            if ((style & WS_CHILD) != 0) {
                return false;
            }

            int exStyle = GetWindowLong(hWnd, GWL_EXSTYLE);
            if ((exStyle & (int)WS_EX_TOOLWINDOW) != 0 && (exStyle & (int)WS_EX_APPWINDOW) == 0) {
                return false;
            }

            IntPtr owner = GetWindow(hWnd, GW_OWNER);
            if (owner != IntPtr.Zero && (exStyle & (int)WS_EX_APPWINDOW) == 0) {
                return false;
            }

            if (GetWindowTextLength(hWnd) == 0 && (exStyle & (int)WS_EX_APPWINDOW) == 0) {
                return false;
            }
            return true;
        }

        private static int CountAltTabWindows() {
            int count = 0;
            EnumWindows((hWnd, lParam) => {
                if (IsAltTabWindow(hWnd)) {
                    count++;
                }
                return true;
            }, IntPtr.Zero);
            return count;
        }

        private const byte VK_MENU = 0x12;
        private const byte VK_TAB = 0x09;
        private const byte VK_SHIFT = 0x10;
        private const byte VK_ESCAPE = 0x1B;
        private const byte VK_HOME = 0x24;
        private const byte VK_LWIN = 0x5B;

        private static void TapKey(byte vk) {
            keybd_event(vk, 0, 0, 0);
            keybd_event(vk, 0, KEYEVENTF_KEYUP, 0);
        }

        private static void TaskSwitcherOpen() {
            keybd_event(VK_MENU, 0, 0, 0);
            TapKey(VK_TAB);
        }

        private static void TaskSwitcherStep(int steps) {
            if (steps == 0) {
                return;
            }
            bool backward = steps < 0;
            int count = Math.Abs(steps);
            for (int i = 0; i < count; i++) {
                if (backward) {
                    keybd_event(VK_SHIFT, 0, 0, 0);
                    TapKey(VK_TAB);
                    keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, 0);
                } else {
                    TapKey(VK_TAB);
                }
            }
        }

        private static void TaskSwitcherCommit() {
            keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
        }

        private static void TaskSwitcherCancel() {
            TapKey(VK_ESCAPE);
            keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
        }

        private static void TaskSwitcherDismiss() {
            TapKey(VK_HOME);
            keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
        }

        private static void TaskSwitcherStartMenu() {
            keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
            TapKey(VK_LWIN);
        }

        private static void RunInputLoop() {
            Console.Error.WriteLine("Input loop started");
            Console.Error.Flush();
            string line;
            while ((line = Console.ReadLine()) != null) {
                try {
                    var parts = line.Split(' ');
                    if (parts.Length == 0) continue;
                    switch (parts[0]) {
                        case "mm":
                            if (parts.Length >= 3) {
                                int x = int.Parse(parts[1]);
                                int y = int.Parse(parts[2]);
                                SetCursorPos(x, y);
                            }
                            break;
                        case "mr":
                            if (parts.Length >= 3) {
                                int dx = int.Parse(parts[1]);
                                int dy = int.Parse(parts[2]);
                                mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0);
                            }
                            break;
                        case "md":
                            if (parts.Length >= 2) {
                                uint flag = 0;
                                if (parts[1] == "l") flag = MOUSEEVENTF_LEFTDOWN;
                                else if (parts[1] == "r") flag = MOUSEEVENTF_RIGHTDOWN;
                                else if (parts[1] == "m") flag = MOUSEEVENTF_MIDDLEDOWN;
                                if (flag != 0) mouse_event(flag, 0, 0, 0, 0);
                            }
                            break;
                        case "mu":
                            if (parts.Length >= 2) {
                                uint flag = 0;
                                if (parts[1] == "l") flag = MOUSEEVENTF_LEFTUP;
                                else if (parts[1] == "r") flag = MOUSEEVENTF_RIGHTUP;
                                else if (parts[1] == "m") flag = MOUSEEVENTF_MIDDLEUP;
                                if (flag != 0) mouse_event(flag, 0, 0, 0, 0);
                            }
                            break;
                        case "mw":
                            if (parts.Length >= 2) {
                                int delta = int.Parse(parts[1]);
                                mouse_event(MOUSEEVENTF_WHEEL, 0, 0, (uint)delta, 0);
                            }
                            break;
                        case "kd":
                            if (parts.Length >= 2) {
                                byte vk = byte.Parse(parts[1]);
                                keybd_event(vk, 0, 0, 0);
                            }
                            break;
                        case "ku":
                            if (parts.Length >= 2) {
                                byte vk = byte.Parse(parts[1]);
                                keybd_event(vk, 0, KEYEVENTF_KEYUP, 0);
                            }
                            break;
                        case "tc":
                            Console.Error.WriteLine("TASK_COUNT:" + CountAltTabWindows());
                            Console.Error.Flush();
                            break;
                        case "ts":
                            if (parts.Length >= 2) {
                                switch (parts[1]) {
                                    case "open":
                                        TaskSwitcherOpen();
                                        break;
                                    case "commit":
                                        TaskSwitcherCommit();
                                        break;
                                    case "cancel":
                                        TaskSwitcherCancel();
                                        break;
                                    case "dismiss":
                                        TaskSwitcherDismiss();
                                        break;
                                    case "start":
                                        TaskSwitcherStartMenu();
                                        break;
                                    case "step":
                                        if (parts.Length >= 3) {
                                            TaskSwitcherStep(int.Parse(parts[2]));
                                        }
                                        break;
                                }
                            }
                            break;
                    }
                } catch (Exception ex) {
                    Console.Error.WriteLine("Input error: " + ex.Message);
                }
            }
        }

        public static void Main(string[] args) {
            EnableDpiAwareness();

            bool probeOnly = false;
            bool taskCountOnly = false;
            bool inputOnly = false;

            for (int i = 0; i < args.Length; i++) {
                if (args[i] == "--raw") {
                    continue;
                } else if (args[i] == "--probe") {
                    probeOnly = true;
                } else if (args[i] == "--task-count") {
                    taskCountOnly = true;
                } else if (args[i] == "--input") {
                    inputOnly = true;
                } else if (args[i] == "--cursor-only") {
                    cursorOnly = true;
                } else if (args[i] == "--monitor" && i + 1 < args.Length) {
                    int newIndex;
                    if (int.TryParse(args[i + 1], out newIndex)) {
                        monitorIndex = newIndex - 1;
                    }
                    i++;
                } else if (args[i] == "--stream-size" && i + 1 < args.Length) {
                    var parts = args[i + 1].Split('x');
                    if (parts.Length == 2 &&
                        int.TryParse(parts[0], out var parsedWidth) &&
                        int.TryParse(parts[1], out var parsedHeight) &&
                        parsedWidth > 0 &&
                        parsedHeight > 0) {
                        streamMaxWidth = parsedWidth;
                        streamMaxHeight = parsedHeight;
                    }
                    i++;
                }
            }

            if (probeOnly) {
                var probe = ResolveMonitorProbeInfo(monitorIndex);
                Console.Out.WriteLine(
                    "{"
                    + $"\"monitor\":{monitorIndex + 1},"
                    + $"\"adapterIndex\":{probe.AdapterIndex},"
                    + $"\"outputIndex\":{probe.OutputIndex},"
                    + $"\"deviceName\":\"{JsonEscape(probe.DeviceName)}\","
                    + $"\"x\":{probe.Bounds.X},"
                    + $"\"y\":{probe.Bounds.Y},"
                    + $"\"width\":{probe.Bounds.Width},"
                    + $"\"height\":{probe.Bounds.Height}"
                    + "}"
                );
                return;
            }

            if (taskCountOnly) {
                Console.Out.WriteLine("{\"count\":" + CountAltTabWindows() + "}");
                return;
            }

            if (inputOnly) {
                RunInputLoop();
                return;
            }

            if (cursorOnly) {
                RunCursorOnlyLoop();
                return;
            }

            using (var stdout = Console.OpenStandardOutput()) {
                FrameCapturer capturer = null;
                try {
                    capturer = new DxgiCapturer();
                } catch (Exception ex) {
                    Console.Error.WriteLine($"DXGI capturer construction failed, using GDI fallback: {ex.Message}");
                    capturer = new GdiCapturer();
                }

                var frameTimer = Stopwatch.StartNew();
                long nextFrameMs = 0;
                int activeMonitor = -1;

                while (running) {
                    try {
                        int currentTarget = monitorIndex;
                        if (activeMonitor != currentTarget) {
                            capturer?.Dispose();
                            try {
                                capturer = new DxgiCapturer();
                                capturer.Initialize(currentTarget);
                            } catch (Exception ex) {
                                Console.Error.WriteLine($"DXGI initialization failed for monitor {currentTarget + 1}, using GDI fallback: {ex.Message}");
                                Console.Error.Flush();
                                capturer = new GdiCapturer();
                                capturer.Initialize(currentTarget);
                            }
                            activeMonitor = currentTarget;
                        }

                        capturer.CaptureRawFrame(stdout);

                        nextFrameMs += FRAME_INTERVAL_MS;
                        long sleepMs = nextFrameMs - frameTimer.ElapsedMilliseconds;
                        if (sleepMs > 0) {
                            Thread.Sleep((int)sleepMs);
                        } else if (sleepMs < -FRAME_INTERVAL_MS * 2) {
                            nextFrameMs = frameTimer.ElapsedMilliseconds;
                        }
                    } catch (Exception ex) {
                        try {
                            Console.Error.WriteLine(ex.ToString());
                            Console.Error.Flush();
                        } catch { }
                        activeMonitor = -1;
                        try { capturer?.Dispose(); } catch { }
                        try {
                            capturer = new GdiCapturer();
                        } catch { }
                    }
                }

                capturer.Dispose();
            }
        }

        private static void EmitCursorMetadataForRegion(int monitorX, int monitorY, int monitorWidth, int monitorHeight, int sourceWidth, int sourceHeight) {
            CURSORINFO cursorInfo = new CURSORINFO();
            cursorInfo.cbSize = Marshal.SizeOf(typeof(CURSORINFO));

            IntPtr cursorHandle = IntPtr.Zero;
            POINT cursorPos;
            bool cursorVisible = false;

            if (GetCursorInfo(ref cursorInfo)) {
                cursorVisible = (cursorInfo.flags & CURSOR_SHOWING) != 0;
                cursorHandle = cursorInfo.hCursor;
                cursorPos = cursorInfo.ptScreenPos;
            } else if (GetCursorPos(out cursorPos)) {
                cursorHandle = GetCursor();
                cursorVisible = true;
            }

            if (!cursorVisible || cursorHandle == IntPtr.Zero) {
                EmitCursorMetadata(false, 0, 0, sourceWidth, sourceHeight, null, 0, 0);
                return;
            }

            int localX = cursorPos.X - monitorX;
            int localY = cursorPos.Y - monitorY;

            if (localX < 0 || localY < 0 || localX >= monitorWidth || localY >= monitorHeight) {
                EmitCursorMetadata(false, 0, 0, sourceWidth, sourceHeight, null, 0, 0);
                return;
            }

            int scaledX = monitorWidth > 0 ? (int)Math.Round((double)localX * sourceWidth / monitorWidth) : localX;
            int scaledY = monitorHeight > 0 ? (int)Math.Round((double)localY * sourceHeight / monitorHeight) : localY;

            int hotX = 0, hotY = 0;
            string cursorBase64 = null;
            ICONINFO iconInfo;
            if (GetIconInfo(cursorHandle, out iconInfo)) {
                hotX = monitorWidth > 0 ? (int)Math.Round((double)iconInfo.xHotspot * sourceWidth / monitorWidth) : iconInfo.xHotspot;
                hotY = monitorHeight > 0 ? (int)Math.Round((double)iconInfo.yHotspot * sourceHeight / monitorHeight) : iconInfo.yHotspot;

                if (cursorHandle != lastCursorHandle || string.IsNullOrEmpty(lastCursorBase64)) {
                    Bitmap cursorBitmap = null;
                    try {
                        cursorBitmap = Bitmap.FromHicon(cursorHandle);
                    } catch {
                        try {
                            cursorBitmap = new Bitmap(64, 64, PixelFormat.Format32bppArgb);
                            using (Graphics g = Graphics.FromImage(cursorBitmap)) {
                                g.Clear(Color.Transparent);
                                IntPtr hdc = g.GetHdc();
                                try {
                                    DrawIconEx(hdc, 0, 0, cursorHandle, 0, 0, 0, IntPtr.Zero, DI_NORMAL);
                                } finally {
                                    g.ReleaseHdc(hdc);
                                }
                            }
                        } catch {
                            cursorBitmap?.Dispose();
                            cursorBitmap = null;
                        }
                    }

                    if (cursorBitmap != null) {
                        try {
                            using (MemoryStream ms = new MemoryStream()) {
                                cursorBitmap.Save(ms, ImageFormat.Png);
                                cursorBase64 = Convert.ToBase64String(ms.ToArray());
                                lastCursorHandle = cursorHandle;
                                lastCursorBase64 = cursorBase64;
                                lastCursorHotX = hotX;
                                lastCursorHotY = hotY;
                            }
                        } catch {
                            cursorBase64 = null;
                        } finally {
                            cursorBitmap.Dispose();
                        }
                    }
                } else {
                    hotX = lastCursorHotX;
                    hotY = lastCursorHotY;
                }

                if (iconInfo.hbmColor != IntPtr.Zero) DeleteObject(iconInfo.hbmColor);
                if (iconInfo.hbmMask != IntPtr.Zero) DeleteObject(iconInfo.hbmMask);
            }

            EmitCursorMetadata(true, scaledX, scaledY, sourceWidth, sourceHeight, cursorBase64, hotX, hotY);
        }

        private static void RunCursorOnlyLoop() {
            var screens = GetOrderedScreens();
            if (monitorIndex < 0 || monitorIndex >= screens.Length) monitorIndex = 0;
            var screen = screens[monitorIndex];
            var bounds = screen.Bounds;
            var sourceSize = new DrawingSize(streamMaxWidth, streamMaxHeight);
            int monitorX = bounds.X;
            int monitorY = bounds.Y;

            Console.Error.WriteLine($"Cursor metadata loop started for monitor {monitorIndex + 1} Source={sourceSize.Width}x{sourceSize.Height}");
            Console.Error.Flush();

            while (running) {
                try {
                    EmitCursorMetadataForRegion(monitorX, monitorY, bounds.Width, bounds.Height, sourceSize.Width, sourceSize.Height);
                    Thread.Sleep(FRAME_INTERVAL_MS);
                } catch (Exception ex) {
                    try {
                        Console.Error.WriteLine(ex.ToString());
                        Console.Error.Flush();
                    } catch { }
                }
            }
        }

        private static void EmitCursorMetadata(bool visible, int x, int y, int width, int height, string base64, int hotX, int hotY) {
            try {
                string imagePart = !string.IsNullOrEmpty(base64) ? $",\"img\":\"{base64}\"" : "";
                string json = $"{{\"type\":\"cursor\",\"visible\":{(visible ? "true" : "false")},\"x\":{x},\"y\":{y},\"w\":{width},\"h\":{height},\"hx\":{hotX},\"hy\":{hotY}{imagePart}}}";
                if (json == lastCursorMetadataJson) {
                    return;
                }
                lastCursorMetadataJson = json;
                Console.Error.WriteLine("CURSOR " + json);
                Console.Error.Flush();
            } catch { }
        }

        private static unsafe void CopyBitmapRegion(Bitmap source, Bitmap dest) {
            var rect = new Rectangle(0, 0, source.Width, source.Height);
            var srcData = source.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            try {
                var dstData = dest.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                try {
                    int rowBytes = Math.Min(srcData.Stride, dstData.Stride);
                    for (int y = 0; y < source.Height; y++) {
                        Buffer.MemoryCopy(
                            (void*)(srcData.Scan0 + y * srcData.Stride),
                            (void*)(dstData.Scan0 + y * dstData.Stride),
                            rowBytes,
                            rowBytes);
                    }
                } finally {
                    dest.UnlockBits(dstData);
                }
            } finally {
                source.UnlockBits(srcData);
            }
        }

        private static void DrawCursorOnBitmap(Bitmap bitmap, int monitorX, int monitorY, int monitorWidth, int monitorHeight) {
            EmitCursorMetadataForRegion(monitorX, monitorY, monitorWidth, monitorHeight, bitmap.Width, bitmap.Height);
        }

        private static unsafe void AlphaBlendBgraOntoBitmap(Bitmap bitmap, int destX, int destY, byte[] src, int width, int height, int pitch) {
            var rect = new Rectangle(0, 0, bitmap.Width, bitmap.Height);
            var destData = bitmap.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
            try {
                byte* destBase = (byte*)destData.Scan0;
                int destStride = destData.Stride;

                for (int row = 0; row < height; row++) {
                    int y = destY + row;
                    if (y < 0 || y >= bitmap.Height) continue;

                    for (int col = 0; col < width; col++) {
                        int x = destX + col;
                        if (x < 0 || x >= bitmap.Width) continue;

                        int srcIdx = row * pitch + col * 4;
                        byte b = src[srcIdx];
                        byte g = src[srcIdx + 1];
                        byte r = src[srcIdx + 2];
                        byte a = src[srcIdx + 3];
                        if (a == 0) continue;

                        byte* d = destBase + y * destStride + x * 4;
                        if (a == 255) {
                            d[0] = b;
                            d[1] = g;
                            d[2] = r;
                            d[3] = 255;
                        } else {
                            float alpha = a / 255f;
                            float inv = 1f - alpha;
                            d[0] = (byte)(b * alpha + d[0] * inv);
                            d[1] = (byte)(g * alpha + d[1] * inv);
                            d[2] = (byte)(r * alpha + d[2] * inv);
                            d[3] = 255;
                        }
                    }
                }
            } finally {
                bitmap.UnlockBits(destData);
            }
        }

        private static unsafe void DrawMonochromePointer(Bitmap bitmap, int destX, int destY, byte[] src, OutduplPointerShapeInfo info) {
            int width = (int)info.Width;
            int height = (int)info.Height / 2;
            int pitch = (int)info.Pitch;

            var rect = new Rectangle(0, 0, bitmap.Width, bitmap.Height);
            var destData = bitmap.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
            try {
                byte* destBase = (byte*)destData.Scan0;
                int destStride = destData.Stride;

                for (int row = 0; row < height; row++) {
                    int y = destY + row;
                    if (y < 0 || y >= bitmap.Height) continue;

                    for (int col = 0; col < width; col++) {
                        int x = destX + col;
                        if (x < 0 || x >= bitmap.Width) continue;

                        int byteIdx = row * pitch + col / 8;
                        int bitMask = 1 << (7 - (col % 8));
                        bool andBit = (src[byteIdx] & bitMask) != 0;
                        bool xorBit = (src[byteIdx + height * pitch] & bitMask) != 0;

                        byte* d = destBase + y * destStride + x * 4;
                        if (!andBit && !xorBit) {
                            d[0] = 0;
                            d[1] = 0;
                            d[2] = 0;
                            d[3] = 255;
                        } else if (!andBit && xorBit) {
                            d[0] = 255;
                            d[1] = 255;
                            d[2] = 255;
                            d[3] = 255;
                        } else if (andBit && !xorBit) {
                            // Transparent - keep destination
                        } else {
                            d[0] ^= 255;
                            d[1] ^= 255;
                            d[2] ^= 255;
                            d[3] = 255;
                        }
                    }
                }
            } finally {
                bitmap.UnlockBits(destData);
            }
        }

        private abstract class FrameCapturer : IDisposable {
            protected Bitmap desktopBitmap;
            protected Bitmap captureBitmap;
            protected Bitmap streamBitmap;
            protected DrawingSize captureSize;
            protected DrawingSize streamSize;
            protected int monitorOriginX;
            protected int monitorOriginY;

            public abstract void Initialize(int monitorIndex);
            public bool CaptureRawFrame(Stream output) {
                if (!CaptureFrameIntoBitmap()) {
                    return false;
                }
                WriteRawFrame(output);
                return true;
            }

            protected abstract bool CaptureFrameIntoBitmap();

            protected void EnsureBitmaps(int captureWidth, int captureHeight) {
                var newCaptureSize = new DrawingSize(captureWidth, captureHeight);
                var newStreamSize = GetStreamSize(captureWidth, captureHeight);

                if (desktopBitmap == null || captureSize != newCaptureSize) {
                    captureBitmap?.Dispose();
                    desktopBitmap?.Dispose();
                    desktopBitmap = new Bitmap(captureWidth, captureHeight, PixelFormat.Format32bppArgb);
                    captureBitmap = new Bitmap(captureWidth, captureHeight, PixelFormat.Format32bppArgb);
                    captureSize = newCaptureSize;
                }

                if (streamBitmap == null || streamSize != newStreamSize) {
                    streamBitmap?.Dispose();
                    streamBitmap = new Bitmap(newStreamSize.Width, newStreamSize.Height, PixelFormat.Format32bppArgb);
                    streamSize = newStreamSize;
                }
            }

            protected virtual void DrawPointerOverlay() {
                DrawCursorOnBitmap(
                    captureBitmap,
                    monitorOriginX,
                    monitorOriginY,
                    captureSize.Width,
                    captureSize.Height);
            }

            protected void PrepareCompositeFrame() {
                CopyBitmapRegion(desktopBitmap, captureBitmap);
            }

            protected void WriteRawFrame(Stream output) {
                PrepareCompositeFrame();
                DrawPointerOverlay();

                Bitmap source = captureBitmap;
                if (streamSize.Width != captureSize.Width || streamSize.Height != captureSize.Height) {
                    using (Graphics g = Graphics.FromImage(streamBitmap)) {
                        g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Low;
                        g.CompositingMode = CompositingMode.SourceCopy;
                        g.CompositingQuality = CompositingQuality.HighSpeed;
                        g.DrawImage(captureBitmap, 0, 0, streamSize.Width, streamSize.Height);
                        g.Flush();
                    }
                    source = streamBitmap;
                }

                var rect = new Rectangle(0, 0, source.Width, source.Height);
                var data = source.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                try {
                    int rowBytes = source.Width * 4;
                    int totalBytes = rowBytes * source.Height;
                    byte[] frame = new byte[totalBytes];
                    if (data.Stride == rowBytes) {
                        Marshal.Copy(data.Scan0, frame, 0, totalBytes);
                    } else {
                        int offset = 0;
                        for (int y = 0; y < source.Height; y++) {
                            Marshal.Copy(data.Scan0 + y * data.Stride, frame, offset, rowBytes);
                            offset += rowBytes;
                        }
                    }
                    output.Write(frame, 0, totalBytes);
                } finally {
                    source.UnlockBits(data);
                }
            }

            public virtual void Dispose() {
                streamBitmap?.Dispose();
                captureBitmap?.Dispose();
                desktopBitmap?.Dispose();
            }
        }

        private sealed class DxgiCapturer : FrameCapturer {
            private static readonly int DXGI_ERROR_WAIT_TIMEOUT = unchecked((int)0x887A0027);
            private static readonly int DXGI_ERROR_ACCESS_LOST = unchecked((int)0x887A0026);

            private IDXGIFactory1 factory;
            private IDXGIAdapter1 adapter;
            private IDXGIOutput1 output;
            private IDXGIOutputDuplication duplication;
            private ID3D11Device device;
            private ID3D11DeviceContext context;
            private ID3D11Texture2D stagingTexture;
            private byte[] pointerShapeBuffer;
            private OutduplPointerShapeInfo pointerShapeInfo;
            private bool hasPointerShape;
            private int pointerX;
            private int pointerY;
            private bool canMapDesktopSurface;
            private bool emittedInitialFrame;
            private int lastEmittedPointerX = int.MinValue;
            private int lastEmittedPointerY = int.MinValue;

            public override void Initialize(int monitorIndex) {
                DisposeDxgi();

                var screens = GetOrderedScreens();
                if (monitorIndex < 0 || monitorIndex >= screens.Length) monitorIndex = 0;
                var target = screens[monitorIndex].Bounds;

                factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();

                bool found = false;
                for (uint i = 0; ; i++) {
                    if (factory.EnumAdapters1(i, out var adp).Failure) break;
                    for (uint j = 0; ; j++) {
                        if (adp.EnumOutputs(j, out var outBase).Failure) break;

                        var out1 = outBase.QueryInterface<IDXGIOutput1>();
                        outBase.Dispose();

                        var rect = out1.Description.DesktopCoordinates;
                        if (rect.Left == target.X && rect.Top == target.Y &&
                            (rect.Right - rect.Left) == target.Width &&
                            (rect.Bottom - rect.Top) == target.Height) {

                            adapter = adp;
                            output = out1;
                            found = true;
                            break;
                        }
                        out1.Dispose();
                    }
                    if (found) { } else { adp.Dispose(); }
                    if (found) break;
                }

                if (!found) {
                    factory.EnumAdapters1(0, out adapter).CheckError();
                    adapter.EnumOutputs((uint)monitorIndex, out var ob).CheckError();
                    output = ob.QueryInterface<IDXGIOutput1>();
                    ob.Dispose();
                }

                D3D11.D3D11CreateDevice(
                    adapter,
                    DriverType.Unknown,
                    DeviceCreationFlags.BgraSupport,
                    null,
                    out device,
                    out context).CheckError();

                duplication = output.DuplicateOutput(device);
                var desc = duplication.Description;
                int width = (int)desc.ModeDescription.Width;
                int height = (int)desc.ModeDescription.Height;
                canMapDesktopSurface = true;
                emittedInitialFrame = false;
                lastEmittedPointerX = int.MinValue;
                lastEmittedPointerY = int.MinValue;

                var outputDesc = output.Description;
                monitorOriginX = outputDesc.DesktopCoordinates.Left;
                monitorOriginY = outputDesc.DesktopCoordinates.Top;

                Console.Error.WriteLine($"DXGI Initialized: Output={output.Description.DeviceName} Origin={monitorOriginX},{monitorOriginY} Size={width}x{height}");
                Console.Error.Flush();

                EnsureBitmaps(width, height);
                CreateStagingTexture(width, height);
                BootstrapPointerShape();
            }

            private void BootstrapPointerShape() {
                IDXGIResource desktopResource = null;
                bool acquired = false;
                try {
                    var result = duplication.AcquireNextFrame(100, out var frameInfo, out desktopResource);
                    if (result.Failure) return;

                    acquired = true;
                    UpdatePointerMetadata(frameInfo);
                    CopyFrameToBitmap(desktopResource, desktopBitmap);
                } catch { } finally {
                    if (acquired) duplication.ReleaseFrame();
                    if (desktopResource != null) desktopResource.Dispose();
                }
            }

            private void CreateStagingTexture(int width, int height) {
                stagingTexture?.Dispose();
                var desc = new Texture2DDescription {
                    Width = (uint)width,
                    Height = (uint)height,
                    MipLevels = 1,
                    ArraySize = 1,
                    Format = Format.B8G8R8A8_UNorm,
                    SampleDescription = new SampleDescription(1, 0),
                    Usage = ResourceUsage.Staging,
                    CPUAccessFlags = CpuAccessFlags.Read,
                    BindFlags = BindFlags.None
                };
                stagingTexture = device.CreateTexture2D(desc);
            }

            private void CopyFrameToBitmap(IDXGIResource desktopResource, Bitmap target) {
                if (canMapDesktopSurface) {
                    try {
                        var mappedSurface = duplication.MapDesktopSurface();
                        try {
                            CopyMappedToBitmap(
                                mappedSurface.DataPointer,
                                (int)mappedSurface.Pitch,
                                target,
                                captureSize.Width,
                                captureSize.Height);
                            return;
                        } finally {
                            duplication.UnMapDesktopSurface();
                        }
                    } catch {
                        canMapDesktopSurface = false;
                    }
                }

                using (var frameTexture = desktopResource.QueryInterface<ID3D11Texture2D>()) {
                    context.CopyResource(stagingTexture, frameTexture);
                }

                var mapped = context.Map(stagingTexture, 0, MapMode.Read, MapFlags.None);
                try {
                    CopyMappedToBitmap(
                        mapped.DataPointer,
                        (int)mapped.RowPitch,
                        target,
                        captureSize.Width,
                        captureSize.Height);
                } finally {
                    context.Unmap(stagingTexture, 0);
                }
            }

            private void CaptureScreenSnapshot(Bitmap target) {
                using (Graphics g = Graphics.FromImage(target)) {
                    g.CopyFromScreen(
                        monitorOriginX,
                        monitorOriginY,
                        0,
                        0,
                        captureSize,
                        CopyPixelOperation.SourceCopy);
                }
            }

            private void RefreshPointerPosition() {
                POINT cursorPos;
                if (!GetCursorPos(out cursorPos)) return;
                int hotspotX = hasPointerShape ? (int)pointerShapeInfo.HotSpot.X : 0;
                int hotspotY = hasPointerShape ? (int)pointerShapeInfo.HotSpot.Y : 0;
                pointerX = cursorPos.X - monitorOriginX - hotspotX;
                pointerY = cursorPos.Y - monitorOriginY - hotspotY;
            }

            private void UpdatePointerMetadata(OutduplFrameInfo frameInfo) {
                if (frameInfo.PointerShapeBufferSize == 0) {
                    hasPointerShape = false;
                    return;
                }

                uint requiredSize = frameInfo.PointerShapeBufferSize;
                if (pointerShapeBuffer == null || pointerShapeBuffer.Length < requiredSize) {
                    pointerShapeBuffer = new byte[requiredSize];
                }

                GCHandle pinned = GCHandle.Alloc(pointerShapeBuffer, GCHandleType.Pinned);
                try {
                    duplication.GetFramePointerShape(
                        requiredSize,
                        pinned.AddrOfPinnedObject(),
                        out requiredSize,
                        out pointerShapeInfo).CheckError();
                } finally {
                    pinned.Free();
                }
                hasPointerShape = true;
            }

            protected override void DrawPointerOverlay() {
                RefreshPointerPosition();
                base.DrawPointerOverlay();
            }

            protected override bool CaptureFrameIntoBitmap() {
                if (duplication == null) throw new InvalidOperationException("DXGI not initialized");

                IDXGIResource desktopResource = null;
                bool acquired = false;
                try {
                    var result = duplication.AcquireNextFrame(DXGI_FRAME_TIMEOUT_MS, out var frameInfo, out desktopResource);
                    if (result.Failure) {
                        if (result.Code == DXGI_ERROR_WAIT_TIMEOUT) {
                            RefreshPointerPosition();
                            if (!emittedInitialFrame) {
                                CaptureScreenSnapshot(desktopBitmap);
                                emittedInitialFrame = true;
                                lastEmittedPointerX = pointerX;
                                lastEmittedPointerY = pointerY;
                                return true;
                            }
                            if (pointerX != lastEmittedPointerX || pointerY != lastEmittedPointerY) {
                                lastEmittedPointerX = pointerX;
                                lastEmittedPointerY = pointerY;
                                return true;
                            }
                            return false;
                        }
                        if (result.Code == DXGI_ERROR_ACCESS_LOST) throw new InvalidOperationException("DXGI access lost");
                        throw new InvalidOperationException("AcquireNextFrame failed");
                    }

                    acquired = true;
                    UpdatePointerMetadata(frameInfo);
                    CopyFrameToBitmap(desktopResource, desktopBitmap);
                    RefreshPointerPosition();
                    lastEmittedPointerX = pointerX;
                    lastEmittedPointerY = pointerY;
                    emittedInitialFrame = true;
                    return true;
                } finally {
                    if (acquired) duplication.ReleaseFrame();
                    if (desktopResource != null) desktopResource.Dispose();
                }
            }

            private void CopyMappedToBitmap(IntPtr source, int srcStride, Bitmap target, int width, int height) {
                var rect = new Rectangle(0, 0, width, height);
                var data = target.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                try {
                    int dstStride = data.Stride;
                    int rowBytes = width * 4;
                    for (int y = 0; y < height; y++) {
                        unsafe {
                            Buffer.MemoryCopy(
                                (void*)(source + y * srcStride),
                                (void*)(data.Scan0 + y * dstStride),
                                rowBytes,
                                rowBytes);
                        }
                    }
                } finally {
                    target.UnlockBits(data);
                }
            }

            public override void Dispose() {
                base.Dispose();
                DisposeDxgi();
            }

            private void DisposeDxgi() {
                stagingTexture?.Dispose(); stagingTexture = null;
                duplication?.Dispose(); duplication = null;
                output?.Dispose(); output = null;
                context?.Dispose(); context = null;
                device?.Dispose(); device = null;
                adapter?.Dispose(); adapter = null;
                factory?.Dispose(); factory = null;
            }
        }

        private sealed class GdiCapturer : FrameCapturer {
            private int srcX;
            private int srcY;

            public override void Initialize(int monitorIndex) {
                var screens = GetOrderedScreens();
                if (monitorIndex < 0 || monitorIndex >= screens.Length) monitorIndex = 0;
                var screen = screens[monitorIndex];
                var bounds = screen.Bounds;

                int width = bounds.Width;
                int height = bounds.Height;
                srcX = bounds.X;
                srcY = bounds.Y;

                if (!dpiAwarenessEnabled) {
                    float scale = GetScaleFactor(screen);
                    width = (int)Math.Round(bounds.Width * scale);
                    height = (int)Math.Round(bounds.Height * scale);
                    srcX = (int)Math.Round(bounds.X * scale);
                    srcY = (int)Math.Round(bounds.Y * scale);
                }

                monitorOriginX = srcX;
                monitorOriginY = srcY;
                EnsureBitmaps(width, height);
            }

            protected override bool CaptureFrameIntoBitmap() {
                using (Graphics g = Graphics.FromImage(desktopBitmap)) {
                    g.CopyFromScreen(srcX, srcY, 0, 0, captureSize, CopyPixelOperation.SourceCopy);
                }
                return true;
            }
        }
    }
}
