package com.example.wifidensepose.ui.main

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val espStatus by viewModel.espStatus.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("ESP32 Connection", style = MaterialTheme.typography.headlineMedium)
        
        Row(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Button(onClick = { viewModel.usbManager.scanDevices() }) {
                Text("Scan USB Devices")
            }
            Spacer(Modifier.width(8.dp))
            if (connectionStatus is ConnectionStatus.Connected) {
                Button(onClick = { viewModel.usbManager.disconnect() }) {
                    Text("Disconnect")
                }
            }
        }

        if (connectionStatus !is ConnectionStatus.Connected) {
            Text("Available Devices:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(availableDevices) { device ->
                    UsbDeviceItem(
                        device = device,
                        onClick = { viewModel.usbManager.requestPermissionAndConnect(device) }
                    )
                }
            }
        } else {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("ESP32 Wi-Fi Status", style = MaterialTheme.typography.titleMedium)
            
            when (val status = espStatus) {
                is EspStatus.Connected -> {
                    Text("Connected to Wi-Fi. IP: ${status.ip}", color = MaterialTheme.colorScheme.primary)
                }
                is EspStatus.Connecting -> {
                    Text("Connecting to ${status.ssid}...")
                }
                else -> {
                    if (status is EspStatus.Failed) {
                        Text("Connection Failed", color = MaterialTheme.colorScheme.error)
                    } else if (status is EspStatus.ReadyForConfig) {
                        Text("Ready for config")
                    }
                    WifiConfigurator { ssid, pass ->
                        viewModel.sendWifiConfig(ssid, pass)
                    }
                }
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Discovered Devices (${scanResults.devices.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(scanResults.devices) { d ->
                    Text("MAC: ${d.mac} | RSSI: ${d.rssi} | BSSID: ${d.bssid}", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Discovered Routers (${scanResults.routers.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(scanResults.routers) { r ->
                    Text("BSSID: ${r.bssid} | RSSI: ${r.rssi}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun WifiConfigurator(onSend: (String, String) -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("SSID") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSend(ssid, password) },
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
        ) {
            Text("Send Config")
        }
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
