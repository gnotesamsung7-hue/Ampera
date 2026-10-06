package com.example.eyebot

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Callbacks from a servo link. Always delivered on the main thread. */
interface ServoLinkEvents {
    fun onLinkStatus(text: String)
    fun onLinkConnected(name: String)
    fun onLinkDisconnected()
    /** A text line from the microcontroller, e.g. "TOUCH:HEAD" or "BTN:2". */
    fun onLinkLine(line: String)
}

/** A connection to the pan-tilt microcontroller. Commands are "latest wins" (coalesced). */
interface ServoLink {
    val label: String
    val isConnected: Boolean
    fun start()
    fun send(line: String)
    fun close()
}

// =============================================================================================
// Bluetooth Low Energy (Nordic UART Service) - ESP32 sketch in firmware/esp32_pantilt
// =============================================================================================

@SuppressLint("MissingPermission")   // MainActivity requests BLUETOOTH_SCAN / CONNECT before start()
class BleServoLink(private val context: Context, private val events: ServoLinkEvents) : ServoLink {

    override val label = "BLE"
    @Volatile override var isConnected = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var wanted = false
    private val assembler = LineAssembler()

    private val lock = Any()
    private var latest: String? = null
    private var writing = false
    private var writeStartedAt = 0L

    override fun start() {
        val a = adapter
        if (a == null) { status("This phone has no Bluetooth"); return }
        if (!a.isEnabled) { status("Bluetooth is off - turn it on and connect again"); return }
        wanted = true
        startScan()
    }

    private fun startScan() {
        if (!wanted || isConnected || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: run { status("BLE scanner unavailable"); return }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build(),
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            status("Searching for $DEVICE_NAME...")
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            status("Bluetooth permission missing")
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        if (wanted && !isConnected) {
            status("$DEVICE_NAME not found - retrying")
            main.postDelayed({ startScan() }, RETRY_MS)
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("BLE scan failed ($errorCode)")
            if (wanted) main.postDelayed({ startScan() }, RETRY_MS)
        }
    }

    private fun connect(device: BluetoothDevice) {
        status("Connecting to ${device.name ?: device.address}...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, gattStatus: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = isConnected
                isConnected = false
                synchronized(lock) { writing = false }
                rx = null
                g.close()
                if (gatt === g) gatt = null
                main.post {
                    if (was) events.onLinkDisconnected()
                    if (wanted) { status("Disconnected - reconnecting"); main.postDelayed({ startScan() }, RETRY_MS) }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            val svc = g.getService(NUS_SERVICE)
            val rxChar = svc?.getCharacteristic(NUS_RX)
            val txChar = svc?.getCharacteristic(NUS_TX)
            if (rxChar == null) {
                status("Device has no UART service")
                g.disconnect()
                return
            }
            rx = rxChar
            if (txChar != null) {
                g.setCharacteristicNotification(txChar, true)
                txChar.getDescriptor(CCCD)?.let { d ->
                    synchronized(lock) { writing = true; writeStartedAt = SystemClock.uptimeMillis() }
                    val ok = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                    if (!ok) synchronized(lock) { writing = false }
                }
            }
            isConnected = true
            val name = g.device.name ?: DEVICE_NAME
            main.post { events.onLinkConnected(name) }
            flush()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) {
            synchronized(lock) { writing = false }
            flush()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, gattStatus: Int) {
            synchronized(lock) { writing = false }
            flush()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                handleIncoming(characteristic.value ?: return)
            }
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        val lines = synchronized(assembler) { assembler.feed(bytes) }
        if (lines.isNotEmpty()) main.post { lines.forEach(events::onLinkLine) }
    }

    override fun send(line: String) {
        synchronized(lock) { latest = line }
        flush()
    }

    private fun flush() {
        val g = gatt ?: return
        val c = rx ?: return
        if (!isConnected) return
        val bytes: ByteArray
        synchronized(lock) {
            if (writing && SystemClock.uptimeMillis() - writeStartedAt < WRITE_WATCHDOG_MS) return
            val l = latest ?: return
            latest = null
            writing = true
            writeStartedAt = SystemClock.uptimeMillis()
            bytes = l.toByteArray(Charsets.US_ASCII)
        }
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.setValue(bytes)
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE write failed", e); false
        }
        if (!ok) synchronized(lock) { writing = false }
    }

    override fun close() {
        wanted = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        isConnected = false
    }

    private fun status(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) events.onLinkStatus(text) else main.post { events.onLinkStatus(text) }
    }

    companion object {
        private const val TAG = "BleServoLink"
        /** Advertised name of the ESP32 sketch. */
        const val DEVICE_NAME = "EyeBot-PanTilt"
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // phone -> ESP32
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // ESP32 -> phone
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val RETRY_MS = 4_000L
        private const val WRITE_WATCHDOG_MS = 500L
    }
}

// =============================================================================================
// USB serial (USB OTG cable to an ESP32 / Arduino: CP210x, CH340, FTDI, CDC-ACM...)
// =============================================================================================

class UsbServoLink(private val context: Context, private val events: ServoLinkEvents) : ServoLink {

    override val label = "USB"
    @Volatile override var isConnected = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val latest = AtomicReference<String?>(null)
    private val writeQueued = AtomicBoolean(false)
    private val assembler = LineAssembler()
    private var receiverRegistered = false
    private val permissionAction = "${context.packageName}.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start()
            else events.onLinkStatus("USB permission denied")
        }
    }

    override fun start() {
        if (isConnected) return
        val driver = findDriver()
        if (driver == null) { events.onLinkStatus("No USB serial device found"); return }
        if (!usb.hasPermission(driver.device)) {
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(context, permissionReceiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags)
            usb.requestPermission(driver.device, pi)
            events.onLinkStatus("Allow USB access on the popup")
            return
        }
        open(driver)
    }

    private fun findDriver(): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()?.let { return it }
        // ESP32-S2/S3/C3 native USB (Espressif VID 0x303A) is plain CDC-ACM.
        val table = ProbeTable()
            .addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
            .addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java)
            .addProduct(0x303A, 0x4001, CdcAcmSerialDriver::class.java)
        return UsbSerialProber(table).findAllDrivers(usb).firstOrNull()
    }

    private fun open(driver: UsbSerialDriver) {
        val conn = usb.openDevice(driver.device)
        if (conn == null) { events.onLinkStatus("Could not open USB device"); return }
        try {
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val lines = synchronized(assembler) { assembler.feed(data) }
                    if (lines.isNotEmpty()) main.post { lines.forEach(events::onLinkLine) }
                }
                override fun onRunError(e: Exception) {
                    main.post { disconnect("USB disconnected") }
                }
            }).also { it.start() }
            isConnected = true
            events.onLinkConnected(driver.javaClass.simpleName.removeSuffix("SerialDriver") + " USB")
        } catch (e: Exception) {
            Log.w(TAG, "USB open failed", e)
            disconnect("USB open failed: ${e.message}")
        }
    }

    override fun send(line: String) {
        if (!isConnected) return
        latest.set(line)
        if (writeQueued.compareAndSet(false, true)) {
            writer.execute {
                writeQueued.set(false)
                val l = latest.getAndSet(null) ?: return@execute
                try { port?.write(l.toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS) }
                catch (e: Exception) { main.post { disconnect("USB write failed") } }
            }
        }
    }

    private fun disconnect(reason: String) {
        val was = isConnected
        isConnected = false
        try { io?.stop() } catch (_: Exception) {}
        io = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        events.onLinkStatus(reason)
        if (was) events.onLinkDisconnected()
    }

    override fun close() {
        if (isConnected) disconnect("USB closed")
        if (receiverRegistered) {
            try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        writer.shutdown()
    }

    companion object {
        private const val TAG = "UsbServoLink"
        const val BAUD = 115200
        private const val WRITE_TIMEOUT_MS = 100
    }
}

// =============================================================================================
// Wi-Fi UDP (ESP8266 / ESP32 on the same network, e.g. the phone's hotspot) - v4
// Ready for the LAFVIN spider robot (ESP8266 has Wi-Fi but no Bluetooth).
// =============================================================================================

class WifiServoLink(private val context: Context, private val events: ServoLinkEvents) : ServoLink {

    override val label = "Wi-Fi"
    @Volatile override var isConnected = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val latest = AtomicReference<String?>(null)
    private val assembler = LineAssembler()
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start() {
        if (running) return
        running = true
        events.onLinkStatus("Searching Wi-Fi for robot boards...")
        thread = Thread(::loop, "eyebot-wifi").also { it.isDaemon = true; it.start() }
    }

    private fun loop() {
        var socket: java.net.DatagramSocket? = null
        try {
            socket = java.net.DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 25
                bind(java.net.InetSocketAddress(0))
            }
            val buf = ByteArray(512)
            var peer: java.net.InetAddress? = null
            var lastProbe = 0L
            var lastPing = 0L
            var lastHeard = 0L
            while (running) {
                val now = SystemClock.uptimeMillis()
                if (peer == null && now - lastProbe > PROBE_EVERY_MS) {
                    lastProbe = now
                    for (addr in broadcastAddresses()) sendTo(socket, "EYEBOT?\n", addr)
                }
                peer?.let { p ->
                    latest.getAndSet(null)?.let { sendTo(socket, it, p) }
                    if (now - lastPing > PING_EVERY_MS) { lastPing = now; sendTo(socket, "PING\n", p) }
                    if (now - lastHeard > TIMEOUT_MS) {
                        peer = null
                        isConnected = false
                        main.post { events.onLinkStatus("Wi-Fi board lost - searching"); events.onLinkDisconnected() }
                    }
                }
                val pkt = java.net.DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                    val bytes = pkt.data.copyOf(pkt.length)
                    val lines = assembler.feed(bytes + '\n'.code.toByte())
                    lastHeard = SystemClock.uptimeMillis()
                    for (l in lines) {
                        if (l.startsWith("EYEBOT:") && peer == null) {
                            peer = pkt.address
                            isConnected = true
                            val name = l.removePrefix("EYEBOT:").ifBlank { "EyeBot board" }
                            main.post { events.onLinkConnected("$name (Wi-Fi)") }
                        } else if (l != "OK" && pkt.address == peer) {
                            main.post { events.onLinkLine(l) }
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
        } catch (e: Exception) {
            Log.w("WifiServoLink", "Wi-Fi link failed", e)
            main.post { events.onLinkStatus("Wi-Fi link error: ${e.message}") }
        } finally {
            socket?.close()
            if (isConnected) { isConnected = false; main.post { events.onLinkDisconnected() } }
        }
    }

    private fun sendTo(socket: java.net.DatagramSocket, line: String, addr: java.net.InetAddress) {
        try {
            val b = line.toByteArray(Charsets.US_ASCII)
            socket.send(java.net.DatagramPacket(b, b.size, addr, PORT))
        } catch (_: Exception) {}
    }

    /** Directed broadcast of every IPv4 network (works on home Wi-Fi and when the phone is the hotspot). */
    private fun broadcastAddresses(): List<java.net.InetAddress> {
        val out = ArrayList<java.net.InetAddress>()
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
        } catch (_: Exception) {}
        try { out += java.net.InetAddress.getByName("255.255.255.255") } catch (_: Exception) {}
        return out.distinct()
    }

    override fun send(line: String) { if (isConnected) latest.set(line) }

    override fun close() {
        running = false
        thread?.interrupt()
        thread = null
    }

    companion object {
        const val PORT = 4210
        private const val PROBE_EVERY_MS = 1_500L
        private const val PING_EVERY_MS = 2_000L
        private const val TIMEOUT_MS = 8_000L
    }
}
