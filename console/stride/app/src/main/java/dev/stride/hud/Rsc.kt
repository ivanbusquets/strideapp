package dev.stride.hud

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * The treadmill, as a standard Bluetooth Running Speed and Cadence sensor.
 *
 * FTMS ([Ftms]) is a treadmill-shaped service, and most watch apps that
 * support it know what a treadmill is. RSC is a footpod-shaped service —
 * narrower, older, and far more widely implemented on the watch side, because
 * almost every running watch and footpod speaks it. WorkoutDoors is the
 * motivating case: it has no FTMS support but does support RSC sensors, which
 * is the same category it offers for a Stryd or a plain footpod.
 *
 * Deliberately a separate GATT server from [Ftms] rather than a second
 * service bolted onto the same one. The two are independent peripherals that
 * happen to describe the same belt: either can be started, stopped, or fail
 * to advertise without the other caring, which matters on a radio that is
 * already juggling a heart-rate central role (see [HeartRate]) and an FTMS
 * peripheral role on the same chip.
 *
 * **Cadence is always zero, and that is deliberate, not a placeholder.**
 * RSC Measurement makes instantaneous cadence mandatory, but this treadmill
 * has no step sensor — the same gap [Ftms] already declines to paper over
 * for exactly the same reason: inventing a cadence from speed would be a
 * number with no source behind it. Zero is a true "not measured", not a
 * guess dressed as one.
 */
class Rsc(private val context: Context) {

    companion object {
        private val TAG = FitProConnection.TAG

        /** Running Speed and Cadence Service. */
        val SERVICE: UUID = uuid16(0x1814)
        /** RSC Measurement: speed, cadence, optionally distance. Notify. */
        val MEASUREMENT: UUID = uuid16(0x2A53)
        /** RSC Feature: what this sensor claims to support. Read. */
        val FEATURE: UUID = uuid16(0x2A54)
        /** Client Characteristic Configuration, the subscribe switch. */
        val CCCD: UUID = uuid16(0x2902)

        private fun uuid16(id: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", id))

        /**
         * RSC Feature bitfield. Bit 1 is "total distance measurement
         * supported" — set, since [Snapshot.distance] is real. Bit 0 (stride
         * length) and bit 2 (multiple sensor locations) are left unset: there
         * is no stride length to report and no sensor location to claim, and
         * claiming either would be the same kind of invention cadence is not
         * getting.
         */
        private const val FEATURES = (1 shl 1)

        /**
         * RSC Measurement flags byte, sent with every frame: bit 1 set (total
         * distance present), bit 0 clear (no stride length), bit 2 left
         * clear — walking is the correct default and this console has no
         * better basis than that to claim "running".
         */
        private const val FLAGS = (1 shl 1)

        private val SERVICE_DATA = byteArrayOf(0x01)

        const val DEVICE_NAME = "STRIDE Treadmill"
        const val NOTIFY_EVERY_MS = 200L
    }

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var measurement: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    private val subs = LinkedHashSet<BluetoothDevice>()
    private val pending = ArrayDeque<BluetoothGattService>()

    @Volatile private var last: Snapshot? = null
    @Volatile private var lastLogAt = 0L
    @Volatile private var lastFrameKey = ""

    private val tickThread = HandlerThread("rsc").apply { start() }
    private val ticker = Handler(tickThread.looper)
    private val tick = object : Runnable {
        override fun run() {
            // The reschedule is in a finally, same reasoning as Ftms.tick:
            // one bad frame must not silently stop every frame after it.
            try {
                last?.let { send(it) }
            } catch (e: Exception) {
                Log.w(TAG, "rsc: frame failed: ${e.message}")
            } finally {
                ticker.postDelayed(this, NOTIFY_EVERY_MS)
            }
        }
    }

    @Volatile var advertising = false
        private set

    val listeners: Int get() = synchronized(subs) { subs.size }

    fun start() {
        val a = adapter ?: run { Log.i(TAG, "rsc: no bluetooth adapter"); return }
        if (!a.isEnabled) { Log.i(TAG, "rsc: bluetooth is off"); return }
        if (!a.isMultipleAdvertisementSupported) {
            Log.i(TAG, "rsc: this radio cannot advertise, skipping")
            return
        }
        if (server != null) return

        val srv = manager?.openGattServer(context, callback) ?: run {
            Log.w(TAG, "rsc: could not open a GATT server"); return
        }
        server = srv

        val feature = BluetoothGattCharacteristic(
            FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { value = featureBytes() }

        val meas = BluetoothGattCharacteristic(
            MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        measurement = meas

        // Sensor Location — must come after `service` exists
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(feature)
        service.addCharacteristic(meas)

        val location = BluetoothGattCharacteristic(
            uuid16(0x2A5D),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { value = byteArrayOf(0x01) }   // 1 = top of shoe, the usual footpod value
        service.addCharacteristic(location)

        pending.addLast(service)

        // Device Information service, queued behind the RSC one
        val info = BluetoothGattService(uuid16(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        info.addCharacteristic(BluetoothGattCharacteristic(
            uuid16(0x2A29), BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ).apply { value = "STRIDE".toByteArray() })
        info.addCharacteristic(BluetoothGattCharacteristic(
            uuid16(0x2A24), BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ).apply { value = "Treadmill".toByteArray() })
        pending.addLast(info)

        addNext()

        advertise(a)
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, NOTIFY_EVERY_MS)
    }

    private fun addNext() {
        val srv = server ?: return
        val next = pending.removeFirstOrNull() ?: return
        srv.addService(next)
    }

    private fun advertise(a: android.bluetooth.BluetoothAdapter) {
        // Shared with Ftms: same adapter, same device name either way, so a
        // watch sees one treadmill whichever service it scanned for.
        try {
            if (a.name != DEVICE_NAME) a.name = DEVICE_NAME
        } catch (e: Exception) {
            Log.w(TAG, "rsc: could not rename the adapter: ${e.message}")
        }

        advertiser = a.bluetoothLeAdvertiser
        val adv = advertiser ?: run { Log.w(TAG, "rsc: no advertiser"); return }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // The RSC service UUID is 16-bit, so it costs far less of the 31-byte
        // advertisement than FTMS's custom 128-bit service data — room is not
        // the constraint here that it is for Ftms.advertise.
        val payload = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .addServiceData(ParcelUuid(SERVICE), SERVICE_DATA)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, payload, scanResponse, advertiseCallback)
    }

    private fun restartAdvertising() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertising = false
        try { advertise(a) } catch (e: Exception) {
            Log.w(TAG, "rsc: could not resume advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.i(TAG, "rsc: advertising as a speed sensor")
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(TAG, "rsc: advertising refused (error $errorCode)")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val name = "running speed and cadence"
            if (status == BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "rsc: published $name")
            else Log.w(TAG, "rsc: $name refused (status $status)")
            addNext()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "rsc: ${device.address} state=$newState status=$status bond=${device.bondState}")
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                synchronized(subs) { subs.remove(device) }
                Log.i(TAG, "rsc: ${device.address} disconnected " +
                        "(status $status${when (status) {
                            8 -> ", link supervision timeout"
                            19 -> ", peer hung up"
                            22 -> ", local host closed it"
                            62 -> ", connection failed to establish"
                            else -> ""
                        }})")
                // Same reasoning as Ftms: Android stops a connectable
                // advertisement the instant it is accepted and never restarts
                // it on its own.
                restartAdvertising()
            } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "rsc: ${device.address} connected (status $status)")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            Log.i(TAG, "rsc: ${device.address} read char ${characteristic.uuid}")
            val value = characteristic.value ?: ByteArray(0)
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0),
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) {
                val on = value.isNotEmpty() && value[0].toInt() != 0
                synchronized(subs) {
                    if (on) subs.add(device) else subs.remove(device)
                }
                Log.i(TAG, "rsc: ${device.address} " +
                        "${if (on) "subscribed to" else "unsubscribed from"} RSC measurement")
                if (on) last?.let { s ->
                    val srv = server ?: return@let
                    push(srv, measurement, listOf(device)) { frame(s) }
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            Log.i(TAG, "rsc: ${device.address} read desc ${descriptor.uuid}")
            val on = synchronized(subs) { subs.contains(device) }
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
            )
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "rsc: ${device.address} mtu $mtu")
        }
    }

    /** Hand over the latest numbers. [tick] transmits on its own clock. */
    fun update(s: Snapshot) { last = s }

    private fun send(s: Snapshot) {
        val srv = server ?: return
        val who = synchronized(subs) { subs.toList() }
        if (who.isEmpty()) return
        val bytes = frame(s)
        logFrame(s, bytes, who.size)
        push(srv, measurement, who) { bytes }
    }

    private fun logFrame(s: Snapshot, b: ByteArray, listeners: Int) {
        val speedMs = ((b[2].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)) / 256.0
        val key = "%.2f".format(speedMs)
        val line = "rsc >> ${"%.2f".format(speedMs)} m/s" +
                "  ${s.distance.toInt()} m" +
                "  (dial ${"%.1f".format(s.speed)} km/h, $listeners listening)"
        val now = SystemClock.elapsedRealtime()
        if (key != lastFrameKey || now - lastLogAt > 30_000L) {
            lastFrameKey = key
            lastLogAt = now
            Log.i(TAG, line)
        }
    }

    private inline fun push(
        srv: BluetoothGattServer,
        c: BluetoothGattCharacteristic?,
        who: List<BluetoothDevice>,
        body: () -> ByteArray,
    ) {
        if (c == null || who.isEmpty()) return
        val bytes = body()
        c.value = bytes
        for (d in who) {
            val ok = try {
                srv.notifyCharacteristicChanged(d, c, false)
            } catch (e: Exception) {
                Log.w(TAG, "rsc: notify to ${d.address} threw: ${e.message}")
                false
            }
            val now = SystemClock.elapsedRealtime()
            if (!ok || now - lastLogAt > 5_000L) {
                lastLogAt = now
                Log.i(TAG, "rsc: notify ${if (ok) "ok" else "REFUSED"} " +
                        "to ${d.address}: ${FitPro.hex(bytes)}")
            }
        }
    }

    private fun featureBytes(): ByteArray {
        val b = ByteArray(2)
        le16(b, 0, FEATURES)
        return b
    }

    /**
     * One RSC Measurement notification.
     *
     * Speed is the belt's own best estimate — [Snapshot.beltKph], the same
     * source [Ftms.frame] falls back to for a belt turning outside a formal
     * session, so an RSC-connected watch and an FTMS-connected one would
     * never disagree about how fast the belt actually is. Cadence is always
     * zero — see the class doc. Distance is whole metres in tenths, per the
     * spec's resolution.
     */
    private fun frame(s: Snapshot): ByteArray {
        val b = ByteArray(10)
        var i = 0
        b[i++] = FLAGS.toByte()
        // Instantaneous speed, m/s at 1/256 resolution.
        val kph = if (s.speed > 0.0) s.speed else s.beltKph
        val ms = kph / 3.6
        i = le16(b, i, Math.round(ms * 256.0).toInt().coerceIn(0, 0xFFFF))
        // Instantaneous cadence, steps/min. Always 0 — no step sensor exists
        // on this console, and a guessed number is worse than an honest zero.
        b[i++] = 0
        // Total distance, 1/10 m, uint32.
        i = le32(b, i, Math.round(s.distance * 10.0).toLong().coerceIn(0L, 0xFFFFFFFFL))
        return b.copyOf(i)
    }

    private fun le16(b: ByteArray, at: Int, v: Int): Int {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        return at + 2
    }

    private fun le32(b: ByteArray, at: Int, v: Long): Int {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
        return at + 4
    }

    fun stop() {
        ticker.removeCallbacks(tick)
        tickThread.quitSafely()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertising = false
        synchronized(subs) { subs.clear() }
        try { server?.close() } catch (_: Exception) { }
        server = null
        measurement = null
    }
}