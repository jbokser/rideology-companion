package com.jbokser.rideology_companion.ui.charts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jbokser.rideology_companion.R

@Composable
fun TextExportMenu(title: String, section: String, plainText: String, messageText: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = "Share ${section.lowercase()}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(2.dp)),
            containerColor = MaterialTheme.colorScheme.surface) {
            DropdownMenuItem(text = { Text("Copy text") }, onClick = {
                expanded = false
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, plainText))
                Toast.makeText(context, "$section copied to clipboard.", Toast.LENGTH_SHORT).show()
            })
            DropdownMenuItem(text = { Text("Share as message") }, onClick = {
                expanded = false
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, messageText)
                }
                try {
                    context.startActivity(Intent.createChooser(send, "Share ${section.lowercase()}"))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "No compatible sharing application is installed.", Toast.LENGTH_LONG).show()
                }
            })
        }
    }
}
