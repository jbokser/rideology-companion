package com.jbokser.rideology_companion.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.jbokser.rideology_companion.R
import com.jbokser.rideology_companion.data.RideSummary
import com.jbokser.rideology_companion.ui.theme.ChartBlue
import com.jbokser.rideology_companion.ui.theme.ChartPurple

@Composable
fun TelemetryZoomScreen(ride: RideSummary, channel: Channel, onClose: () -> Unit) {
    val telemetry = requireNotNull(ride.telemetry)
    val speed = channel == Channel.SPEED
    val title = if (speed) "Speed (km/h)" else "Engine RPM"
    BackHandler(onBack = onClose)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("telemetry_zoom")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                ChartExportMenu(ride, if (speed) ChartImageKind.SPEED else ChartImageKind.RPM)
            }
            HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            TelemetryPanel(telemetry, channel, if (speed) "Speed" else "Engine RPM", if (speed) "km/h" else "rpm",
                if (speed) ChartBlue else ChartPurple, if (speed) telemetry.maximumSpeed else telemetry.maximumRpm,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp), showTitle = false)
        }
    }
}
