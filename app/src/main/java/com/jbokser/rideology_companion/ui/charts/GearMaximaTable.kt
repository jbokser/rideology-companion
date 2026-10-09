package com.jbokser.rideology_companion.ui.charts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.jbokser.rideology_companion.ui.theme.RideAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.jbokser.rideology_companion.data.RideReport
import com.jbokser.rideology_companion.data.RideSummary

@Composable
fun GearMaximaTable(ride: RideSummary) {
    if (ride.gears.isEmpty()) {
        Text("No valid numbered gear data available.")
        return
    }
    val rows = RideReport.gearRows(ride)
    val highestRpm = RideReport.highlightedGearRpm(ride)
    Row(Modifier.fillMaxWidth()) {
        for (column in 0..2) Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                rows.forEachIndexed { index, row ->
                    val highlight = column == 1 && index > 0 && highestRpm != null && ride.gears[index - 1].rpm == highestRpm
                    Text(row[column], Modifier.fillMaxWidth(), textAlign = if (index == 0) TextAlign.Center else TextAlign.End,
                        color = if (highlight) RideAmber else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
