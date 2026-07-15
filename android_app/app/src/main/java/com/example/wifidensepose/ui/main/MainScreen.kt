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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

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
            Text("Radar Map View", style = MaterialTheme.typography.titleMedium)
            RadarMap(devices = scanResults.devices, modifier = Modifier.padding(vertical = 8.dp))

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Discovered Devices (${scanResults.devices.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(scanResults.devices) { d ->
                    val distance = 10.0.pow((-50.0 - d.rssi) / 20.0)
                    Text("MAC: ${d.mac} | RSSI: ${d.rssi} | Dist: %.2fm".format(distance), style = MaterialTheme.typography.bodySmall)
                }
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Discovered Routers (${scanResults.routers.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(scanResults.routers) { r ->
                    val distance = 10.0.pow((-50.0 - r.rssi) / 20.0)
                    Text("BSSID: ${r.bssid} | RSSI: ${r.rssi} | Dist: %.2fm".format(distance), style = MaterialTheme.typography.bodySmall)
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

@Composable
fun RadarMap(devices: List<DiscoveredDevice>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(250.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = minOf(size.width, size.height) / 2f
        
        // Draw radar circles
        for (i in 1..4) {
            drawCircle(
                color = Color.Green.copy(alpha = 0.3f),
                radius = maxRadius * (i / 4f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        
        // Draw crosshairs
        drawLine(
            color = Color.Green.copy(alpha = 0.3f),
            start = Offset(center.x, 0f),
            end = Offset(center.x, size.height),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.Green.copy(alpha = 0.3f),
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = 1.dp.toPx()
        )
        
        // Draw devices
        devices.forEach { device ->
            val distance = 10.0.pow((-50.0 - device.rssi) / 20.0)
            // Cap distance for visualization to 20 meters
            val normalizedDistance = (distance / 20.0).coerceIn(0.0, 1.0)
            val radius = maxRadius * normalizedDistance.toFloat()
            
            // Generate a deterministic angle based on MAC address hash
            val angle = (device.mac.hashCode() % 360) * (Math.PI / 180.0)
            
            val x = center.x + (radius * cos(angle)).toFloat()
            val y = center.y + (radius * sin(angle)).toFloat()
            
            drawCircle(
                color = Color.Red,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}
