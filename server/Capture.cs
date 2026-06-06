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

public class Capture {
    private static volatile int monitorIndex = 0;
    private static volatile bool running = true;
    private static bool dpiAwarenessEnabled = false;

    private const int TARGET_FPS = 60;
    private const int FRAME_INTERVAL_MS = 1000 / TARGET_FPS;
    private const int MAX_STREAM_WIDTH = 1920;
    private const int MAX_STREAM_HEIGHT = 1080;
    private const long JPEG_QUALITY = 45L;

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
    private static extern IntPtr GetCursor();

    [DllImport("user32.dll")]
    private static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr hIcon, int cx, int cy, int frame, IntPtr flicker, int flags);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);

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
            (double)MAX_STREAM_WIDTH / captureWidth,
            (double)MAX_STREAM_HEIGHT / captureHeight);
        if (scale >= 1.0) {
            return new DrawingSize(captureWidth, captureHeight);
        }
        return new DrawingSize(
            Math.Max(1, (int)Math.Round(captureWidth * scale)),
            Math.Max(1, (int)Math.Round(captureHeight * scale)));
    }

    private static ImageCodecInfo GetEncoderInfo(string mimeType) {
        foreach (var encoder in ImageCodecInfo.GetImageEncoders()) {
            if (encoder.MimeType == mimeType) return encoder;
        }
        return null;
    }

    public static void Main() {
        EnableDpiAwareness();

        Thread commandThread = new Thread(() => {
            while (running) {
                string line = Console.ReadLine();
                if (line == null) break;
                if (line.StartsWith("MONITOR ")) {
                    int newIndex;
                    if (int.TryParse(line.Substring(8), out newIndex)) {
                        monitorIndex = newIndex - 1;
                    }
                }
            }
        });
        commandThread.IsBackground = true;
        commandThread.Start();

        var jpegCodec = GetEncoderInfo("image/jpeg");
        var encoderParameters = new EncoderParameters(1);
        encoderParameters.Param[0] = new EncoderParameter(Encoder.Quality, JPEG_QUALITY);
        var lengthBytes = new byte[4];

        using (var ms = new MemoryStream(512 * 1024))
        using (var stdout = Console.OpenStandardOutput()) {
            FrameCapturer capturer = null;
            try {
                capturer = new DxgiCapturer();
            } catch {
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
                        } catch {
                            capturer = new GdiCapturer();
                            capturer.Initialize(currentTarget);
                        }
                        activeMonitor = currentTarget;
                    }

                    capturer.CaptureFrame(ms, jpegCodec, encoderParameters);
                    if (ms.Length == 0) {
                        continue;
                    }

                    byte[] buffer = ms.ToArray();

                    lengthBytes[0] = (byte)buffer.Length;
                    lengthBytes[1] = (byte)(buffer.Length >> 8);
                    lengthBytes[2] = (byte)(buffer.Length >> 16);
                    lengthBytes[3] = (byte)(buffer.Length >> 24);
                    stdout.Write(lengthBytes, 0, 4);
                    stdout.Write(buffer, 0, buffer.Length);
                    stdout.Flush();
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

                nextFrameMs += FRAME_INTERVAL_MS;
                long sleepMs = nextFrameMs - frameTimer.ElapsedMilliseconds;
                if (sleepMs > 0) {
                    Thread.Sleep((int)sleepMs);
                } else if (sleepMs < -FRAME_INTERVAL_MS * 2) {
                    nextFrameMs = frameTimer.ElapsedMilliseconds;
                }
            }

            capturer.Dispose();
        }
    }

    private static IntPtr lastCursorHandle = IntPtr.Zero;
    private static string lastCursorBase64 = "";
    private static int lastHx = 0;
    private static int lastHy = 0;

    private static void DrawCursorOnBitmap(Bitmap bitmap, int monitorX, int monitorY, int monitorWidth, int monitorHeight) {
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
            EmitCursorMetadata(false, 0, 0, bitmap.Width, bitmap.Height, null, 0, 0);
            return;
        }

        int localX = cursorPos.X - monitorX;
        int localY = cursorPos.Y - monitorY;

        if (localX < 0 || localY < 0 || localX >= monitorWidth || localY >= monitorHeight) {
            EmitCursorMetadata(false, 0, 0, bitmap.Width, bitmap.Height, null, 0, 0);
            return;
        }

        string currentBase64 = null;
        int hotX = 0, hotY = 0;

        if (cursorHandle != lastCursorHandle || string.IsNullOrEmpty(lastCursorBase64)) {
            ICONINFO iconInfo;
            if (GetIconInfo(cursorHandle, out iconInfo)) {
                hotX = iconInfo.xHotspot;
                hotY = iconInfo.yHotspot;

                Bitmap cursorBmp = null;
                try {
                    cursorBmp = Bitmap.FromHicon(cursorHandle);
                } catch {
                    try {
                        cursorBmp = new Bitmap(64, 64, PixelFormat.Format32bppArgb);
                        using (Graphics g = Graphics.FromImage(cursorBmp)) {
                            g.Clear(Color.Transparent);
                            IntPtr hdc = g.GetHdc();
                            try {
                                DrawIconEx(hdc, 0, 0, cursorHandle, 0, 0, 0, IntPtr.Zero, DI_NORMAL);
                            } finally {
                                g.ReleaseHdc(hdc);
                            }
                        }
                    } catch {
                        cursorBmp?.Dispose();
                        cursorBmp = null;
                    }
                }

                if (cursorBmp != null) {
                    try {
                        using (MemoryStream ms = new MemoryStream()) {
                            cursorBmp.Save(ms, ImageFormat.Png);
                            currentBase64 = Convert.ToBase64String(ms.ToArray());
                            lastCursorBase64 = currentBase64;
                            lastCursorHandle = cursorHandle;
                            lastHx = hotX;
                            lastHy = hotY;
                        }
                    } catch {
                        currentBase64 = null;
                    } finally {
                        cursorBmp.Dispose();
                    }
                }

                if (iconInfo.hbmColor != IntPtr.Zero) DeleteObject(iconInfo.hbmColor);
                if (iconInfo.hbmMask != IntPtr.Zero) DeleteObject(iconInfo.hbmMask);
            }
        } else {
            currentBase64 = lastCursorBase64;
            hotX = lastHx;
            hotY = lastHy;
        }

        EmitCursorMetadata(true, localX, localY, bitmap.Width, bitmap.Height, currentBase64, hotX, hotY);
        // We no longer draw anything on the bitmap here.
    }

    private static void EmitCursorMetadata(bool visible, int x, int y, int width, int height, string base64, int hx, int hy) {
        try {
            string imgPart = !string.IsNullOrEmpty(base64) ? $",\"img\":\"{base64}\",\"hx\":{hx},\"hy\":{hy}" : "";
            string json = $"{{\"visible\":{(visible ? "true" : "false")},\"x\":{x},\"y\":{y},\"w\":{width},\"h\":{height}{imgPart}}}";
            Console.Error.WriteLine("CURSOR " + json);
            Console.Error.Flush();
        } catch { }
    }

    private static void DrawPointerMarker(Graphics g, int x, int y) {
        // Larger, high-contrast marker
        const int size = 64;
        const int arm = 32;

        g.SmoothingMode = SmoothingMode.AntiAlias;

        // Outer glow/shadow
        using (var shadowBrush = new SolidBrush(Color.FromArgb(120, Color.Black))) {
            g.FillEllipse(shadowBrush, x - size / 2 - 2, y - size / 2 - 2, size + 4, size + 4);
        }

        // Inner fill
        using (var fillBrush = new SolidBrush(Color.FromArgb(160, Color.Red))) {
            g.FillEllipse(fillBrush, x - size / 2, y - size / 2, size, size);
        }

        // Crosshair
        using (var p = new Pen(Color.Black, 6)) {
            g.DrawEllipse(p, x - size / 2, y - size / 2, size, size);
            g.DrawLine(p, x - arm - 4, y, x + arm + 4, y);
            g.DrawLine(p, x, y - arm - 4, x, y + arm + 4);
        }

        using (var p = new Pen(Color.Yellow, 3)) {
            g.DrawEllipse(p, x - size / 2 + 1, y - size / 2 + 1, size - 2, size - 2);
            g.DrawLine(p, x - arm, y, x + arm, y);
            g.DrawLine(p, x, y - arm, x, y + arm);
        }
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
        public abstract void CaptureFrame(MemoryStream ms, ImageCodecInfo jpegCodec, EncoderParameters encoderParams);

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
                streamBitmap = new Bitmap(newStreamSize.Width, newStreamSize.Height, PixelFormat.Format24bppRgb);
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

        protected void EncodeFrame(MemoryStream ms, ImageCodecInfo jpegCodec, EncoderParameters encoderParams) {
            PrepareCompositeFrame();
            DrawPointerOverlay();

            ms.SetLength(0);
            if (streamSize.Width == captureSize.Width && streamSize.Height == captureSize.Height) {
                captureBitmap.Save(ms, jpegCodec, encoderParams);
            } else {
                using (Graphics g = Graphics.FromImage(streamBitmap)) {
                    g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Low;
                    g.CompositingMode = CompositingMode.SourceCopy;
                    g.CompositingQuality = CompositingQuality.HighSpeed;
                    g.DrawImage(captureBitmap, 0, 0, streamSize.Width, streamSize.Height);
                    g.Flush();
                }
                streamBitmap.Save(ms, jpegCodec, encoderParams);
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

        public override void Initialize(int monitorIndex) {
            DisposeDxgi();

            var screens = Screen.AllScreens;
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
                    // Fix: Compare against target.X and target.Y (Rectangle properties)
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
                if (found) {
                    // Success, keep adp
                } else {
                    adp.Dispose();
                }
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
                if (result.Failure) {
                    return;
                }

                acquired = true;
                UpdatePointerMetadata(frameInfo);

                using (var frameTexture = desktopResource.QueryInterface<ID3D11Texture2D>()) {
                    context.CopyResource(stagingTexture, frameTexture);
                }

                var mapped = context.Map(stagingTexture, 0, MapMode.Read, MapFlags.None);
                try {
                    CopyMappedToBitmap(
                        mapped.DataPointer,
                        (int)mapped.RowPitch,
                        desktopBitmap,
                        captureSize.Width,
                        captureSize.Height);
                } finally {
                    context.Unmap(stagingTexture, 0);
                }
            } catch {
                // Pointer metadata will arrive on a later frame
            } finally {
                if (acquired) {
                    duplication.ReleaseFrame();
                }
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

        private void RefreshPointerPosition() {
            POINT cursorPos;
            if (!GetCursorPos(out cursorPos)) {
                return;
            }

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
            // The DXGI pointer-shape path can fail silently on some desktops.
            // Always use the GDI cursor overlay as the visible fallback.
            base.DrawPointerOverlay();
        }

        private bool DrawDxgiPointer(Bitmap bitmap, int destX, int destY) {
            var shapeType = (PointerShapeType)pointerShapeInfo.Type;
            if (shapeType == PointerShapeType.Color ||
                shapeType == PointerShapeType.MaskedColor) {
                AlphaBlendBgraOntoBitmap(
                    bitmap,
                    destX,
                    destY,
                    pointerShapeBuffer,
                    (int)pointerShapeInfo.Width,
                    (int)pointerShapeInfo.Height,
                    (int)pointerShapeInfo.Pitch);
                return true;
            } else if (shapeType == PointerShapeType.Monochrome) {
                DrawMonochromePointer(bitmap, destX, destY, pointerShapeBuffer, pointerShapeInfo);
                return true;
            }

            return false;
        }

        public override void CaptureFrame(MemoryStream ms, ImageCodecInfo jpegCodec, EncoderParameters encoderParams) {
            if (duplication == null) throw new InvalidOperationException("DXGI not initialized");

            IDXGIResource desktopResource = null;
            bool acquired = false;
            try {
                var result = duplication.AcquireNextFrame(16, out var frameInfo, out desktopResource);
                if (result.Failure) {
                    if (result.Code == DXGI_ERROR_WAIT_TIMEOUT) {
                        EncodeFrame(ms, jpegCodec, encoderParams);
                        return;
                    }
                    if (result.Code == DXGI_ERROR_ACCESS_LOST) {
                        throw new InvalidOperationException("DXGI access lost");
                    }
                    throw new InvalidOperationException("AcquireNextFrame failed");
                }

                acquired = true;
                UpdatePointerMetadata(frameInfo);

                using (var frameTexture = desktopResource.QueryInterface<ID3D11Texture2D>()) {
                    context.CopyResource(stagingTexture, frameTexture);
                }

                var mapped = context.Map(stagingTexture, 0, MapMode.Read, MapFlags.None);
                try {
                    CopyMappedToBitmap(
                        mapped.DataPointer,
                        (int)mapped.RowPitch,
                        desktopBitmap,
                        captureSize.Width,
                        captureSize.Height);
                } finally {
                    context.Unmap(stagingTexture, 0);
                }

                EncodeFrame(ms, jpegCodec, encoderParams);
            } finally {
                if (acquired) {
                    duplication.ReleaseFrame();
                }
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
            stagingTexture?.Dispose();
            stagingTexture = null;
            duplication?.Dispose();
            duplication = null;
            output?.Dispose();
            output = null;
            context?.Dispose();
            context = null;
            device?.Dispose();
            device = null;
            adapter?.Dispose();
            adapter = null;
            factory?.Dispose();
            factory = null;
        }
    }

    private sealed class GdiCapturer : FrameCapturer {
        private int srcX;
        private int srcY;

        public override void Initialize(int monitorIndex) {
            var screens = Screen.AllScreens;
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

        public override void CaptureFrame(MemoryStream ms, ImageCodecInfo jpegCodec, EncoderParameters encoderParams) {
            using (Graphics g = Graphics.FromImage(desktopBitmap)) {
                g.CopyFromScreen(srcX, srcY, 0, 0, captureSize, CopyPixelOperation.SourceCopy);
            }
            EncodeFrame(ms, jpegCodec, encoderParams);
        }
    }
}
