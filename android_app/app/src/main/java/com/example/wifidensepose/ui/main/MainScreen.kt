package com.example.wifidensepose.ui.main

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.wifidensepose.usb.ConnectionStatus

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel(
        factory = MainScreenViewModelFactory(LocalContext.current.applicationContext as Application)
    )
) {
    val availableDevices by viewModel.availableDevices.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val serialData by viewModel.serialData.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("ESP32 USB Connection", style = MaterialTheme.typography.headlineMedium)
        
        Button(
            onClick = { viewModel.usbManager.scanDevices() },
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        ) {
            Text("Scan USB Devices")
        }

        Text("Available Devices:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(0.3f)) {
            items(availableDevices) { device ->
                UsbDeviceItem(
                    device = device,
                    onClick = { viewModel.usbManager.requestPermissionAndConnect(device) }
                )
            }
        }
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Status: ${connectionStatus.toText()}", style = MaterialTheme.typography.titleMedium)
        
        if (connectionStatus is ConnectionStatus.Connected) {
            Button(
                onClick = { viewModel.usbManager.disconnect() },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Disconnect")
            }
        }
        
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Serial Data:", style = MaterialTheme.typography.titleMedium)
        val scrollState = rememberScrollState()
        Text(
            text = serialData,
            modifier = Modifier
                .weight(0.7f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(8.dp)
        )
    }
}

@Composable
fun UsbDeviceItem(device: UsbDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Column {
            Text(device.productName ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
            Text("VID: ${device.vendorId} PID: ${device.productId}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun ConnectionStatus.toText(): String = when (this) {
    ConnectionStatus.Connecting -> "Connecting..."
    is ConnectionStatus.Connected -> "Connected to ${device.productName ?: "Device"}"
    ConnectionStatus.Disconnected -> "Disconnected"
    is ConnectionStatus.Error -> "Error: $message"
}
