package com.example.arviewersync.ui

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arviewersync.viewmodel.ARViewerViewModel

/**
 * Top overlay: live yaw / pitch / roll / FPS / camera-facing / roll-comp
 * status. Laid out edge-to-edge under the status bar (statusBarsPadding keeps
 * it visible in a notch).
 */
@Composable
fun HudOverlay(
    vm: ARViewerViewModel,
    modifier: Modifier = Modifier
) {
    val orientation by vm.orientation.collectAsState()
    val fps by vm.fps.collectAsState()
    val cameraFacing by vm.cameraFacing.collectAsState()
    val rollComp by vm.rollCompensationEnabled.collectAsState()
    val fov by vm.fov.collectAsState()
    val ready by vm.bridgeReady.collectAsState()
    val err by vm.lastError.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(8.dp)
    ) {
        // Line 1: orientation + FPS
        HudChipRow {
            val o = orientation
            Text(
                text = if (o != null)
                    "yaw %+.1f°  pitch %+.1f°  roll %+.1f°".format(
                        o.yawDeg, o.pitchDeg, o.rollDeg
                    )
                else "yaw --°  pitch --°  roll --°",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                text = "FPS $fps",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        // Line 2: status flags
        HudChipRow {
            Text(
                text = (if (cameraFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK") +
                    "  FOV ${fov.toInt()}°  " +
                    "ROLL-COMP " + (if (rollComp) "ON" else "OFF") +
                    "  " + (if (ready) "VIEWER ✓" else "VIEWER…"),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        err?.let { e ->
            HudChipRow {
                Text(
                    text = "ERR: $e",
                    color = Color(0xFFFF5252),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun HudChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                Color(0xAA000000),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}
