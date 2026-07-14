package com.example.wifidensepose.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.wifidensepose.usb.ConnectionStatus
import com.example.wifidensepose.usb.UsbSerialManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    val usbManager = UsbSerialManager(application)
    
    val availableDevices = usbManager.availableDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val connectionStatus = usbManager.connectionStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.Disconnected)
        
    val serialData = usbManager.serialData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
        
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
