package com.jbokser.rideology_companion.ui.charts

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jbokser.rideology_companion.R
import com.jbokser.rideology_companion.data.RideSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChartExportMenu(ride: RideSummary, kind: ChartImageKind, onCopy: (() -> Unit)? = null, onShareText: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val export: (Boolean) -> Unit = { save ->
        expanded = false
        scope.launch(Dispatchers.Main.immediate) {
            busy = true
            var bitmap: Bitmap? = null
            try {
                val image = withContext(Dispatchers.Default) { ChartImageExporter.render(context, ride, kind) }
                bitmap = image
                if (save) {
                    withContext(Dispatchers.IO) { ChartImageExporter.saveToGallery(context, image, kind) }
                    Toast.makeText(context, "Saved to Pictures / Rideology Companion.", Toast.LENGTH_LONG).show()
                } else {
                    val send = withContext(Dispatchers.IO) { ChartImageExporter.shareIntent(context, image, kind, ride.title) }
                    context.startActivity(Intent.createChooser(send, "Share ${kind.label} JPG"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                val message = if (exception is ActivityNotFoundException) "No compatible sharing application is installed."
                    else "The image could not be ${if (save) "saved" else "shared"}. Please try again."
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally {
                bitmap?.recycle()
                busy = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) export(true) else Toast.makeText(context, "Storage permission is required to save to the gallery on this Android version.", Toast.LENGTH_LONG).show()
    }
    val hasData = when (kind) {
        ChartImageKind.SUMMARY, ChartImageKind.GEARS -> true
        ChartImageKind.SPEED -> ride.telemetry?.maximumSpeed != null && ride.telemetry.intervals.isNotEmpty()
        ChartImageKind.RPM -> ride.telemetry?.maximumRpm != null && ride.telemetry.intervals.isNotEmpty()
        ChartImageKind.TELEMETRY -> ride.telemetry?.intervals?.isNotEmpty() == true
        ChartImageKind.DISTRIBUTION -> ride.speedDistribution.isNotEmpty()
    }
    Box {
        IconButton(onClick = { expanded = true }, enabled = hasData && !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_share), contentDescription = if (kind == ChartImageKind.SUMMARY) "Share ride summary" else if (kind == ChartImageKind.GEARS) "Share max for each gear" else "Share ${kind.label} chart")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(2.dp)),
            containerColor = MaterialTheme.colorScheme.surface) {
            onCopy?.let { copy -> DropdownMenuItem(text = { Text("Copy text") }, onClick = { expanded = false; copy() }) }
            onShareText?.let { share -> DropdownMenuItem(text = { Text("Share as message") }, onClick = { expanded = false; share() }) }
            DropdownMenuItem(text = { Text("Share JPG") }, onClick = { export(false) })
            DropdownMenuItem(text = { Text("Save to gallery") }, onClick = {
                expanded = false
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else export(true)
            })
        }
    }
}
