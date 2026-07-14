package com.example.wifidensepose.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

class UsbSerialManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private val _availableDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val availableDevices: StateFlow<List<UsbDevice>> = _availableDevices.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _serialData = MutableStateFlow<String>("")
    val serialData: StateFlow<String> = _serialData.asStateFlow()

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val buffer = java.lang.StringBuilder()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.wifidensepose.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    } else {
                        _connectionStatus.value = ConnectionStatus.Error("Permission denied for device $device")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action || UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                scanDevices()
                if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                    disconnect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        scanDevices()
    }

    fun scanDevices() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        _availableDevices.value = drivers.map { it.device }
    }

    fun requestPermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
            _connectionStatus.value = ConnectionStatus.Connecting
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).find { it.device == device }
        if (driver == null) {
            _connectionStatus.value = ConnectionStatus.Error("Driver not found")
            return
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            _connectionStatus.value = ConnectionStatus.Error("Could not open device")
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            _connectionStatus.value = ConnectionStatus.Error("No ports available")
            return
        }

        try {
            port.open(connection)
            port.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            
            ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val text = String(data, Charsets.UTF_8)
                    buffer.append(text)
                    var index = buffer.indexOf('\n')
                    while (index != -1) {
                        val line = buffer.substring(0, index).trim()
                        if (line.isNotEmpty()) {
                            _serialData.value = line
                        }
                        buffer.delete(0, index + 1)
                        index = buffer.indexOf('\n')
                    }
                }
                override fun onRunError(e: Exception) {
                    _connectionStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                    disconnect()
                }
            })
            Executors.newSingleThreadExecutor().submit(ioManager)
            _connectionStatus.value = ConnectionStatus.Connected(device)
        } catch (e: IOException) {
            _connectionStatus.value = ConnectionStatus.Error("Connection failed: ${e.message}")
            disconnect()
        }
    }

    fun disconnect() {
        _connectionStatus.value = ConnectionStatus.Disconnected
        ioManager?.stop()
        ioManager = null
        try {
            serialPort?.close()
        } catch (e: IOException) {
            // Ignore
        }
        serialPort = null
        buffer.clear()
    }

    fun write(data: String) {
        val port = serialPort ?: return
        try {
            val bytes = data.toByteArray(Charsets.UTF_8)
            port.write(bytes, 2000)
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.Error("Write failed: ${e.message}")
        }
    }

    fun cleanup() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }
}

sealed interface ConnectionStatus {
    object Disconnected : ConnectionStatus
    object Connecting : ConnectionStatus
    data class Connected(val device: UsbDevice) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}
