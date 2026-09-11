package com.example.arviewersync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ResetSettings
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arviewersync.viewmodel.ARViewerViewModel

/**
 * Bottom control bar with: Flip Camera / Roll-Comp toggle / Reset View /
 * Load GLTF (file picker) / Reset FOV. Laid out edge-to-edge above the
 * navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlBar(
    vm: ARViewerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    // File picker for .glb / .gltf
    val gltfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // WebView can load content:// URIs directly when allowContentAccess is true.
            vm.loadGltfUrl(uri.toString())
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000), shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(
                icon = Icons.Filled.Cameraswitch,
                label = "Flip"
            ) { vm.flipCamera() }
            ControlButton(
                icon = Icons.Filled.ScreenRotation,
                label = "Roll"
            ) { vm.toggleRollCompensation() }
            ControlButton(
                icon = Icons.Filled.Restore,
                label = "Reset"
            ) { vm.resetView() }
            ControlButton(
                icon = Icons.Filled.UploadFile,
                label = "GLTF"
            ) {
                // Open a small dialog with "Pick file" + "URL" options.
                showUrlDialog = true
            }
            ControlButton(
                icon = Icons.Filled.ResetSettings,
                label = "FOV"
            ) { vm.setFov(75f) }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Load GLTF model") },
            text = {
                Column {
                    Text("Pick a .glb/.gltf file from storage:")
                    TextButton(onClick = {
                        showUrlDialog = false
                        gltfPicker.launch("*/*")
                    }) { Text("Pick file") }
                    Text("…or paste a URL:")
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        placeholder = { Text("https://…/model.glb") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = urlInput.trim()
                    if (u.isNotEmpty()) {
                        vm.loadGltfUrl(u)
                        urlInput = ""
                    }
                    showUrlDialog = false
                }) { Text("Load URL") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp
        )
    }
}
