package com.example.myapplication

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Head tracker for RayNeo Air-series glasses.
 *
 * Uses Madgwick AHRS (gyro + accelerometer) in quaternion space so large head turns
 * stay consistent. [reset] stores a reference quaternion — it never restarts the filter.
 */
class RayNeoGlassesTracker private constructor(context: Context) {

    enum class VizObject {
        AXIS,
        GLASSES
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Yaw / pitch / roll in radians relative to last [reset]. */
    @Volatile
    var orientationRad: FloatArray = floatArrayOf(0f, 0f, 0f)
        private set

    /** Display quaternion [w,x,y,z] relative to last [reset]. Used for axis rendering. */
    @Volatile
    var displayQuat: FloatArray = floatArrayOf(1f, 0f, 0f, 0f)
        private set

    val eulerDeg: FloatArray
        get() = floatArrayOf(
            Math.toDegrees(orientationRad[0].toDouble()).toFloat(),
            Math.toDegrees(orientationRad[1].toDouble()).toFloat(),
            Math.toDegrees(orientationRad[2].toDouble()).toFloat()
        )

    @Volatile
    var status: String = "Idle"
        private set

    @Volatile
    var usingGlasses: Boolean = false
        private set

    @Volatile
    var vizObject: VizObject = VizObject.AXIS
        private set

    @Volatile
    private var running = false
    private var readerThread: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var permissionHost: Context? = null
    private var phoneListener: SensorEventListener? = null
    private var pendingDevice: UsbDevice? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val fuser = HeadOrientationFuser()
    private val phoneQuat = floatArrayOf(1f, 0f, 0f, 0f)
    private val phoneRefQuat = floatArrayOf(1f, 0f, 0f, 0f)

    fun usbDeviceSummary(): String {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) return "No USB devices visible to the app."
        return devices.joinToString("\n") { device ->
            val marker = if (device == findGlasses()) " ← glasses" else ""
            "• ${device.productName ?: "?"} " +
                "(vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)})$marker"
        }
    }

    fun ensureUsbAccess(activity: Activity, onResult: (Boolean) -> Unit) {
        logAllDevices()
        val device = findGlasses()
        if (device == null) {
            status = "No glasses USB interface (${usbManager.deviceList.size} device(s))"
            onResult(false)
            return
        }
        pendingDevice = device
        if (usbManager.hasPermission(device)) {
            status = "USB access granted"
            onResult(true)
            return
        }

        status = "Allow USB on phone screen…"
        permissionCallback = onResult
        requestPermissionFromActivity(activity, device)
    }

    fun reset() {
        if (usingGlasses) {
            fuser.resetReference()
        } else {
            phoneRefQuat[0] = phoneQuat[0]
            phoneRefQuat[1] = phoneQuat[1]
            phoneRefQuat[2] = phoneQuat[2]
            phoneRefQuat[3] = phoneQuat[3]
        }
        orientationRad = floatArrayOf(0f, 0f, 0f)
        displayQuat = floatArrayOf(1f, 0f, 0f, 0f)
        Log.d(TAG, "Orientation reset (reference quaternion)")
    }

    fun cycleVizObject(): VizObject {
        vizObject = when (vizObject) {
            VizObject.AXIS -> VizObject.GLASSES
            VizObject.GLASSES -> VizObject.AXIS
        }
        Log.d(TAG, "Viz object set to $vizObject")
        return vizObject
    }

    fun start() {
        if (running) return
        running = true
        val device = findGlasses()
        if (device == null) {
            status = "No glasses (phone sensor)"
            startPhoneFallback()
            return
        }
        if (!usbManager.hasPermission(device)) {
            status = "USB not permitted (phone sensor)"
            startPhoneFallback()
            return
        }
        openAndStream(device)
    }

    fun stop() {
        running = false
        permissionCallback = null
        pendingDevice = null
        readerThread?.interrupt()
        readerThread = null
        unregisterPermissionReceiver()
        phoneListener?.let { sensorManager.unregisterListener(it) }
        phoneListener = null
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        usbInterface = null
        usingGlasses = false
        if (!running) status = "Idle"
    }

    private fun findGlasses(): UsbDevice? {
        val all = usbManager.deviceList.values.toList()
        val candidates = all.filter { isLikelyGlasses(it) }
        return candidates.firstOrNull { device ->
            (0 until device.interfaceCount).any { i -> hasInterruptIn(device.getInterface(i)) }
        } ?: candidates.firstOrNull()
    }

    private fun isLikelyGlasses(device: UsbDevice): Boolean {
        if (device.vendorId == RAYNEO_VENDOR_ID) return true
        val name = (device.productName ?: "").lowercase()
        val vendor = (device.manufacturerName ?: "").lowercase()
        return name.contains("rayneo") ||
            name.contains("glasses") ||
            name.contains("smart glass") ||
            name.contains("nxtwear") ||
            vendor.contains("rayneo") ||
            vendor.contains("tcl") ||
            vendor.contains("t & a")
    }

    private fun hasInterruptIn(iface: UsbInterface): Boolean {
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                ep.direction == UsbConstants.USB_DIR_IN
            ) return true
        }
        return false
    }

    private fun requestPermissionFromActivity(activity: Activity, device: UsbDevice) {
        unregisterPermissionReceiver()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                unregisterPermissionReceiver()

                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted device=${device.deviceName}")
                permissionCallback?.invoke(granted)
                permissionCallback = null

                if (granted && running) {
                    openAndStream(device)
                } else if (!granted) {
                    status = "USB denied (phone sensor)"
                    if (running) startPhoneFallback()
                }
            }
        }
        permissionReceiver = receiver
        permissionHost = activity

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(receiver, filter)
        }

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags)

        Log.d(TAG, "Requesting USB permission for ${device.deviceName} vid=0x${device.vendorId.toString(16)}")
        usbManager.requestPermission(device, pi)
    }

    private fun unregisterPermissionReceiver() {
        val host = permissionHost ?: appContext
        permissionReceiver?.let {
            try {
                host.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        permissionReceiver = null
        permissionHost = null
    }

    private fun logAllDevices() {
        val devices = usbManager.deviceList.values
        Log.d(TAG, "USB device count=${devices.size}")
        for (device in devices) {
            Log.d(
                TAG,
                "  ${device.deviceName} vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)} " +
                    "product=${device.productName} mfg=${device.manufacturerName} " +
                    "ifaces=${device.interfaceCount} likely=${isLikelyGlasses(device)}"
            )
        }
    }

    private fun openAndStream(device: UsbDevice) {
        var chosenInterface: UsbInterface? = null
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var localIn: UsbEndpoint? = null
            var localOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) localIn = ep else localOut = ep
            }
            if (localIn != null) {
                chosenInterface = iface
                epIn = localIn
                epOut = localOut
                break
            }
        }

        if (chosenInterface == null || epIn == null) {
            status = "No IMU interface (phone sensor)"
            startPhoneFallback()
            return
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            status = "USB open failed (phone sensor)"
            startPhoneFallback()
            return
        }
        if (!conn.claimInterface(chosenInterface, true)) {
            conn.close()
            status = "USB claim failed (phone sensor)"
            startPhoneFallback()
            return
        }

        connection = conn
        usbInterface = chosenInterface
        usingGlasses = true
        status = "Glasses: ${device.productName ?: "RayNeo"}"
        Log.d(TAG, "Opened RayNeo glasses ${device.deviceName} ifc=${chosenInterface.id}")

        val inEp = epIn
        val outEp = epOut
        val ifaceNumber = chosenInterface.id
        fuser.resetAll()

        readerThread = thread(name = "RayNeoIMU", isDaemon = true) {
            enableImu(conn, outEp, ifaceNumber)

            val buffer = ByteArray(64)
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            var lastProcessedTick = -1L
            var lastSampleNanos = System.nanoTime()
            var lastReenable = System.currentTimeMillis()

            while (running && !Thread.currentThread().isInterrupted) {
                val read = try {
                    conn.bulkTransfer(inEp, buffer, buffer.size, 250)
                } catch (e: Exception) {
                    Log.e(TAG, "bulkTransfer error", e)
                    -1
                }

                if (read < 64) {
                    val now = System.currentTimeMillis()
                    if (now - lastReenable > 1000) {
                        enableImu(conn, outEp, ifaceNumber)
                        lastReenable = now
                    }
                    continue
                }

                if (buffer[0] != INBOUND_MAGIC || buffer[1] != ACK_IMU_DATA) continue

                bb.clear()
                val ax = bb.getFloat(4)
                val ay = bb.getFloat(8)
                val az = bb.getFloat(12)
                val gxDps = bb.getFloat(16)
                val gyDps = bb.getFloat(20)
                val gzDps = bb.getFloat(24)
                val tick = bb.getInt(40).toLong() and 0xFFFF_FFFFL
                // Same tick = duplicate HID report; integrating again multiplies rotation.
                if (tick == lastProcessedTick) continue
                lastProcessedTick = tick

                val now = System.nanoTime()
                val dt = ((now - lastSampleNanos) / 1_000_000_000f).coerceIn(0.0005f, 0.02f)
                lastSampleNanos = now

                fuser.update(gxDps, gyDps, gzDps, ax, ay, az, dt)
                val display = fuser.displayPose()
                orientationRad = display.first
                displayQuat = display.second
            }
        }
    }

    private fun enableImu(conn: UsbDeviceConnection, outEp: UsbEndpoint?, ifaceNumber: Int) {
        val frame = ByteArray(64)
        frame[0] = OUTBOUND_MAGIC
        frame[1] = CMD_IMU_ON
        frame[2] = 0x00
        var sent = -1
        if (outEp != null) {
            sent = try {
                conn.bulkTransfer(outEp, frame, frame.size, 500)
            } catch (_: Exception) {
                -1
            }
        }
        if (sent != frame.size) {
            try {
                conn.controlTransfer(0x21, 0x09, 0x0200, ifaceNumber, frame, frame.size, 500)
            } catch (_: Exception) {
            }
        }
    }

    private fun startPhoneFallback() {
        usingGlasses = false
        fuser.resetAll()
        phoneRefQuat[0] = 1f; phoneRefQuat[1] = 0f; phoneRefQuat[2] = 0f; phoneRefQuat[3] = 0f
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: run {
            status = "No sensor available"
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
                rotationVectorToQuat(event.values, phoneQuat)
                val rel = quatMultiply(quatConjugate(phoneRefQuat), phoneQuat)
                orientationRad = quatToYawPitchRoll(rel)
                displayQuat = rel.copyOf()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        phoneListener = listener
        sensorManager.registerListener(listener, rv, SensorManager.SENSOR_DELAY_GAME)
    }

    companion object {
        private const val TAG = "RayNeoTracker"
        const val ACTION_USB_PERMISSION = "com.example.myapplication.USB_PERMISSION"
        private const val RAYNEO_VENDOR_ID = 0x3941
        private const val DEG2RAD = 0.017453292f

        private const val OUTBOUND_MAGIC = 0x66.toByte()
        private const val INBOUND_MAGIC = 0x99.toByte()
        private const val CMD_IMU_ON = 0x01.toByte()
        private const val ACK_IMU_DATA = 0x65.toByte()

        @Volatile
        private var instance: RayNeoGlassesTracker? = null

        fun getInstance(context: Context): RayNeoGlassesTracker {
            return instance ?: synchronized(this) {
                instance ?: RayNeoGlassesTracker(context.applicationContext).also { instance = it }
            }
        }

        /** Rotate a vector by quaternion [w,x,y,z]. */
        fun rotateVectorByQuat(vx: Float, vy: Float, vz: Float, q: FloatArray): FloatArray {
            val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]
            val tx = 2f * (qy * vz - qz * vy)
            val ty = 2f * (qz * vx - qx * vz)
            val tz = 2f * (qx * vy - qy * vx)
            return floatArrayOf(
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx)
            )
        }

        private fun rotationVectorToQuat(rv: FloatArray, out: FloatArray) {
            val x = rv.getOrElse(0) { 0f }
            val y = rv.getOrElse(1) { 0f }
            val z = rv.getOrElse(2) { 0f }
            val w = if (rv.size >= 4) rv[3] else {
                val m = 1f - x * x - y * y - z * z
                sqrt(if (m > 0f) m else 0f)
            }
            out[0] = w; out[1] = x; out[2] = y; out[3] = z
            quatNormalize(out)
        }

        private fun quatConjugate(q: FloatArray): FloatArray =
            floatArrayOf(q[0], -q[1], -q[2], -q[3])

        private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
            val aw = a[0]; val ax = a[1]; val ay = a[2]; val az = a[3]
            val bw = b[0]; val bx = b[1]; val by = b[2]; val bz = b[3]
            return floatArrayOf(
                aw * bw - ax * bx - ay * by - az * bz,
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw
            )
        }

        private fun quatNormalize(q: FloatArray) {
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (n > 1e-6f) {
                q[0] /= n; q[1] /= n; q[2] /= n; q[3] /= n
            }
        }

        private fun quatToYawPitchRoll(q: FloatArray): FloatArray {
            val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
            val sinp = 2f * (w * y - z * x)
            val pitch = if (sinp > 1f) asin(1f) else if (sinp < -1f) asin(-1f) else asin(sinp)
            val roll = atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y))
            val yaw = atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z))
            return floatArrayOf(yaw, pitch, roll)
        }
    }
}

/**
 * Madgwick AHRS with reference-quaternion reset.
 * Gravity (accel) keeps pitch/roll honest through large motions; gyro keeps them responsive.
 */
private class HeadOrientationFuser {
    private val ahrs = MadgwickAhrs()
    private val refQuat = floatArrayOf(1f, 0f, 0f, 0f)
    private val currentQuat = floatArrayOf(1f, 0f, 0f, 0f)
    private val displayQuat = floatArrayOf(1f, 0f, 0f, 0f)

    private var biasGx = 0f
    private var biasGy = 0f
    private var biasGz = 0f
    private var biasSamples = 0
    private var biasReady = false
    private var needsAccelInit = true
    private var needsRefAfterBias = false

    fun resetAll() {
        ahrs.reset()
        refQuat[0] = 1f; refQuat[1] = 0f; refQuat[2] = 0f; refQuat[3] = 0f
        biasGx = 0f; biasGy = 0f; biasGz = 0f
        biasSamples = 0; biasReady = false
        needsAccelInit = true
        needsRefAfterBias = false
    }

    fun resetReference() {
        ahrs.copyQuatTo(refQuat)
    }

    fun displayPose(): Pair<FloatArray, FloatArray> {
        ahrs.copyQuatTo(currentQuat)
        val rel = quatMultiply(quatConjugate(refQuat), currentQuat)
        quatNormalize(rel)
        displayQuat[0] = rel[0]; displayQuat[1] = rel[1]
        displayQuat[2] = rel[2]; displayQuat[3] = rel[3]
        return quatToYawPitchRoll(rel) to displayQuat.copyOf()
    }

    fun update(
        gxDps: Float, gyDps: Float, gzDps: Float,
        ax: Float, ay: Float, az: Float,
        dt: Float
    ) {
        if (needsAccelInit) {
            val aNorm = sqrt(ax * ax + ay * ay + az * az)
            if (aNorm < 5f) return
            ahrs.initFromAccelerometer(ax, ay, az)
            resetReference()
            needsAccelInit = false
            if (!biasReady) needsRefAfterBias = true
            Log.d("RayNeoTracker", "AHRS initialized from gravity, auto-zeroed reference")
        }

        val gyroMag = sqrt(gxDps * gxDps + gyDps * gyDps + gzDps * gzDps)
        val still = gyroMag < STILL_GYRO_DPS

        val wasBiasReady = biasReady
        if (!biasReady) {
            if (still) {
                biasGx = (biasGx * biasSamples + gxDps) / (biasSamples + 1)
                biasGy = (biasGy * biasSamples + gyDps) / (biasSamples + 1)
                biasGz = (biasGz * biasSamples + gzDps) / (biasSamples + 1)
                biasSamples++
                if (biasSamples >= 200) biasReady = true
            }
        } else if (still) {
            val k = 0.0015f
            biasGx = biasGx * (1f - k) + gxDps * k
            biasGy = biasGy * (1f - k) + gyDps * k
            biasGz = biasGz * (1f - k) + gzDps * k
        }

        if (!wasBiasReady && biasReady && needsRefAfterBias) {
            resetReference()
            needsRefAfterBias = false
            Log.d("RayNeoTracker", "Gyro bias ready, re-zeroed reference")
        }

        val gx = (gxDps - if (biasReady) biasGx else 0f) * DEG2RAD
        val gy = (gyDps - if (biasReady) biasGy else 0f) * DEG2RAD
        val gz = (gzDps - if (biasReady) biasGz else 0f) * DEG2RAD

        // Trust gyro during fast turns; lean on gravity when moving slowly.
        val beta = if (gyroMag > FAST_GYRO_DPS) 0.008f else 0.035f
        ahrs.update(gx, gy, gz, ax, ay, az, dt, beta)
    }

    private fun quatConjugate(q: FloatArray): FloatArray =
        floatArrayOf(q[0], -q[1], -q[2], -q[3])

    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val aw = a[0]; val ax = a[1]; val ay = a[2]; val az = a[3]
        val bw = b[0]; val bx = b[1]; val by = b[2]; val bz = b[3]
        return floatArrayOf(
            aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw
        )
    }

    private fun quatNormalize(q: FloatArray) {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n > 1e-6f) {
            q[0] /= n; q[1] /= n; q[2] /= n; q[3] /= n
        }
    }

    private fun quatToYawPitchRoll(q: FloatArray): FloatArray {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val sinp = 2f * (w * y - z * x)
        val pitch = if (sinp > 1f) asin(1f) else if (sinp < -1f) asin(-1f) else asin(sinp)
        val roll = atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y))
        val yaw = atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z))
        return floatArrayOf(yaw, pitch, roll)
    }

    companion object {
        private const val DEG2RAD = 0.017453292f
        private const val STILL_GYRO_DPS = 2f
        private const val FAST_GYRO_DPS = 25f
    }
}

private class MadgwickAhrs {
    private var q0 = 1f
    private var q1 = 0f
    private var q2 = 0f
    private var q3 = 0f

    fun reset() {
        q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f
    }

    /** Seed tilt from gravity so Madgwick doesn't crawl from identity over many seconds. */
    fun initFromAccelerometer(ax: Float, ay: Float, az: Float) {
        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm < 1e-5f) return
        val axN = ax / norm
        val ayN = ay / norm
        val azN = az / norm
        val pitch = atan2(-axN, sqrt(ayN * ayN + azN * azN))
        val roll = atan2(ayN, azN)
        val cp = cos(pitch * 0.5f)
        val sp = sin(pitch * 0.5f)
        val cr = cos(roll * 0.5f)
        val sr = sin(roll * 0.5f)
        // Yaw-pitch-roll (ZYX) with yaw = 0.
        q0 = cr * cp
        q1 = sr * cp
        q2 = cr * sp
        q3 = sr * sp
        val qNorm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (qNorm > 1e-6f) {
            q0 /= qNorm; q1 /= qNorm; q2 /= qNorm; q3 /= qNorm
        }
    }

    fun copyQuatTo(out: FloatArray) {
        out[0] = q0; out[1] = q1; out[2] = q2; out[3] = q3
    }

    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float, beta: Float) {
        var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm > 1e-5f) {
            val axN = ax / norm
            val ayN = ay / norm
            val azN = az / norm

            val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
            val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
            val _8q1 = 8f * q1; val _8q2 = 8f * q2
            val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3

            var s0 = _4q0 * q2q2 + _2q2 * axN + _4q0 * q1q1 - _2q1 * ayN
            var s1 = _4q1 * q3q3 - _2q3 * axN + 4f * q0q0 * q1 - _2q0 * ayN - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * azN
            var s2 = 4f * q0q0 * q2 + _2q0 * axN + _4q2 * q3q3 - _2q3 * ayN - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * azN
            var s3 = 4f * q1q1 * q3 - _2q1 * axN + 4f * q2q2 * q3 - _2q2 * ayN

            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-6f) {
                s0 /= sNorm; s1 /= sNorm; s2 /= sNorm; s3 /= sNorm
                qDot1 -= beta * s0
                qDot2 -= beta * s1
                qDot3 -= beta * s2
                qDot4 -= beta * s3
            }
        }

        q0 += qDot1 * dt
        q1 += qDot2 * dt
        q2 += qDot3 * dt
        q3 += qDot4 * dt

        val qNorm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (qNorm > 1e-6f) {
            q0 /= qNorm; q1 /= qNorm; q2 /= qNorm; q3 /= qNorm
        }
    }
}
