package com.example.wifidensepose.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.wifidensepose.usb.ConnectionStatus
import com.example.wifidensepose.usb.UsbSerialManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

data class DiscoveredDevice(val mac: String, val bssid: String, val rssi: Int)
data class DiscoveredRouter(val bssid: String, val rssi: Int)
data class ScanResults(val routers: List<DiscoveredRouter>, val devices: List<DiscoveredDevice>)

sealed interface EspStatus {
    object Unknown : EspStatus
    object ReadyForConfig : EspStatus
    data class Connecting(val ssid: String) : EspStatus
    data class Connected(val ip: String) : EspStatus
    object Failed : EspStatus
}

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    val usbManager = UsbSerialManager(application)
    
    val availableDevices = usbManager.availableDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val connectionStatus = usbManager.connectionStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.Disconnected)
        
    val serialData = usbManager.serialData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _espStatus = MutableStateFlow<EspStatus>(EspStatus.Unknown)
    val espStatus: StateFlow<EspStatus> = _espStatus.asStateFlow()

    private val _scanResults = MutableStateFlow<ScanResults>(ScanResults(emptyList(), emptyList()))
    val scanResults: StateFlow<ScanResults> = _scanResults.asStateFlow()

    init {
        viewModelScope.launch {
            usbManager.serialData.collect { line ->
                if (line.isBlank() || !line.trim().startsWith("{")) return@collect
                try {
                    val json = JSONObject(line)
                    if (json.has("status")) {
                        when (json.getString("status")) {
                            "ready_for_config" -> _espStatus.value = EspStatus.ReadyForConfig
                            "connecting" -> _espStatus.value = EspStatus.Connecting(json.optString("ssid"))
                            "connected" -> _espStatus.value = EspStatus.Connected(json.optString("ip"))
                            "failed" -> _espStatus.value = EspStatus.Failed
                        }
                    } else if (json.has("type") && json.getString("type") == "scan_results") {
                        val routersArray = json.optJSONArray("routers")
                        val devicesArray = json.optJSONArray("devices")
                        
                        val parsedRouters = mutableListOf<DiscoveredRouter>()
                        if (routersArray != null) {
                            for (i in 0 until routersArray.length()) {
                                val obj = routersArray.getJSONObject(i)
                                parsedRouters.add(DiscoveredRouter(obj.getString("bssid"), obj.getInt("rssi")))
                            }
                        }
                        
                        val parsedDevices = mutableListOf<DiscoveredDevice>()
                        if (devicesArray != null) {
                            for (i in 0 until devicesArray.length()) {
                                val obj = devicesArray.getJSONObject(i)
                                parsedDevices.add(DiscoveredDevice(obj.getString("mac"), obj.getString("bssid"), obj.getInt("rssi")))
                            }
                        }
                        
                        _scanResults.value = ScanResults(parsedRouters, parsedDevices)
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors for partial lines or invalid json
                }
            }
        }
    }

    fun sendWifiConfig(ssid: String, pass: String) {
        try {
            val json = JSONObject().apply {
                put("ssid", ssid)
                put("password", pass)
            }
            usbManager.write(json.toString() + "\n")
        } catch (e: Exception) { }
    }
        
    override fun onCleared() {
        super.onCleared()
        usbManager.cleanup()
    }
}

class MainScreenViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainScreenViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainScreenViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
