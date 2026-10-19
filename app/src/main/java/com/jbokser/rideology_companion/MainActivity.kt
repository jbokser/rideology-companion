package com.jbokser.rideology_companion

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import com.jbokser.rideology_companion.ui.charts.TextExportMenu
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.FileProvider
import com.jbokser.rideology_companion.data.*
import com.jbokser.rideology_companion.ui.theme.RideologyCompanionTheme
import com.jbokser.rideology_companion.ui.theme.RideTitleStyle
import com.jbokser.rideology_companion.ui.theme.CsvHistoryTitleStyle
import com.jbokser.rideology_companion.ui.charts.TelemetryCharts
import com.jbokser.rideology_companion.ui.charts.SpeedDistributionChart
import com.jbokser.rideology_companion.ui.charts.ChartExportMenu
import com.jbokser.rideology_companion.ui.charts.ChartImageKind
import kotlinx.coroutines.*
import android.content.pm.ActivityInfo
import com.jbokser.rideology_companion.ui.charts.Channel
import com.jbokser.rideology_companion.ui.charts.TelemetryZoomScreen

class MainActivity : ComponentActivity() {
    private val gearRecordStore by lazy { GearRecordStore(java.io.File(filesDir, "gear_records")) }
    private var gearRecords by mutableStateOf<List<GearRecord>>(emptyList())
    private var recordMessage by mutableStateOf<String?>(null)
    private var pendingRecordDelete by mutableStateOf<GearRecord?>(null)
    private val historyStore by lazy { CsvImportHistory(java.io.File(filesDir, "csv_history")) }
    private var historyEntries by mutableStateOf<List<CsvHistoryEntry>>(emptyList())
    private var showingHistory by mutableStateOf(false)
    private var zoomChannel by mutableStateOf<Channel?>(null)
    private var summary by mutableStateOf<RideSummary?>(null)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var selectedUri: Uri? = null
    private var importJob: Job? = null
    private var sharedFiles by mutableStateOf<List<Pair<Uri, String>>>(emptyList())

    private var pendingCsvSave: CsvHistoryEntry? = null
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val entry = pendingCsvSave
        pendingCsvSave = null
        if (uri != null && entry != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        historyStore.file(entry).inputStream().use { it.copyTo(output) }
                    } ?: throw IllegalArgumentException("The destination could not be opened.")
                }
                Toast.makeText(this@MainActivity, "CSV saved.", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "The CSV could not be saved.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveHistoryCsv(entry: CsvHistoryEntry) {
        pendingCsvSave = entry
        try {
            saveCsv.launch(entry.exportFileName())
        } catch (_: ActivityNotFoundException) {
            pendingCsvSave = null
            Toast.makeText(this, "No compatible file-saving application is installed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareHistoryCsv(entry: CsvHistoryEntry) {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val directory = java.io.File(cacheDir, "csv_exports/${entry.id}")
                    check(directory.isDirectory || directory.mkdirs())
                    val file = java.io.File(directory, entry.exportFileName())
                    historyStore.file(entry).copyTo(file, overwrite = true)
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.chartfiles", file)
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, entry.title)
                    clipData = ClipData.newRawUri("CSV", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share CSV"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "No compatible sharing application is installed.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "The CSV could not be shared.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var archiveBusy by mutableStateOf(false)
    private var archiveMessage by mutableStateOf<String?>(null)
    private val saveArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) archiveTask {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")?.use { CsvHistoryZip.export(it, historyStore) }
                    ?: throw IllegalArgumentException("The destination could not be opened.")
            }
            archiveMessage = "All saved CSV files were exported to ZIP."
        }
    }
    private val openArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) archiveTask {
            val broken = mutableListOf<String>()
            val result = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    CsvHistoryZip.import(input, historyStore) { ride ->
                        ensureActive()
                        val update = gearRecordStore.record(ride)
                        if (update.broken.isNotEmpty()) broken += "${ride.rider}@${ride.bike}\n" + update.broken.joinToString("\n")
                    }
                } ?: throw IllegalArgumentException("The ZIP could not be opened.")
            }
            archiveMessage = "Imported ${result.imported} CSV files. Skipped ${result.skipped} invalid CSV files." +
                if (broken.isNotEmpty()) "\n\nNew historical records\n\n" + broken.joinToString("\n\n") else ""
        }
    }

    private fun archiveTask(action: suspend () -> Unit) {
        if (archiveBusy) return
        lifecycleScope.launch {
            archiveBusy = true
            try {
                importJob?.join()
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                archiveMessage = exception.message ?: "The ZIP operation could not be completed."
            } finally {
                try {
                    val updated = withContext(Dispatchers.IO) { historyStore.entries() to gearRecordStore.entries() }
                    historyEntries = updated.first
                    gearRecords = updated.second
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    archiveMessage = "The saved CSV history or historical records could not be refreshed."
                } finally {
                    archiveBusy = false
                }
            }
        }
    }

    private fun saveHistoryZip() {
        try {
            saveArchive.launch("rideology-csv-history.zip")
        } catch (_: ActivityNotFoundException) {
            archiveMessage = "No compatible file-saving application is installed."
        }
    }

    private fun importHistoryZip() {
        try {
            openArchive.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            archiveMessage = "No compatible file-opening application is installed."
        }
    }

    private fun shareHistoryZip() = archiveTask {
        val uri = withContext(Dispatchers.IO) {
            val directory = java.io.File(cacheDir, "zip_exports")
            check(directory.isDirectory || directory.mkdirs())
            val file = java.io.File.createTempFile("rideology-csv-history-", ".zip", directory)
            file.outputStream().use { CsvHistoryZip.export(it, historyStore) }
            FileProvider.getUriForFile(this@MainActivity, "$packageName.chartfiles", file)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("CSV history ZIP", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share CSV history ZIP"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("pending_csv_id")?.let { id ->
            savedInstanceState.getString("pending_csv_title")?.let { pendingCsvSave = CsvHistoryEntry(id, it) }
        }
        zoomChannel = savedInstanceState?.getString("zoom_channel")?.let { value -> Channel.entries.find { it.name == value } }
        if (zoomChannel != null) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        lifecycleScope.launch {
            try {
                historyEntries = withContext(Dispatchers.IO) { historyStore.entries() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "CSV history could not be read.", Toast.LENGTH_LONG).show()
            }
        }
        lifecycleScope.launch {
            try {
                gearRecords = withContext(Dispatchers.IO) { gearRecordStore.entries() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Historical gear records could not be read.", Toast.LENGTH_LONG).show()
            }
        }
        setContent {
            RideologyCompanionTheme {
                val screenState = rememberSaveableStateHolder()
                val zoom = zoomChannel
                val ride = summary
                if (showingHistory) CsvHistoryScreen(historyEntries,
                    onBack = { showingHistory = false },
                    onSelect = { load(Uri.fromFile(historyStore.file(it))) }, onDelete = ::deleteHistoryEntry,
                    onShare = ::shareHistoryCsv, onSave = ::saveHistoryCsv,
                    onExportZip = ::saveHistoryZip, onShareZip = ::shareHistoryZip, onImportZip = ::importHistoryZip, archiveBusy = archiveBusy)
                else if (zoom != null && ride != null) TelemetryZoomScreen(ride, zoom, ::closeZoom)
                else screenState.SaveableStateProvider("ride") {
                    RideScreen(summary, loading, error, ::load, ::openMap, ::copySummary, ::shareSummary, ::openRideology, ::openZoom, { showingHistory = true }, gearRecords, { pendingRecordDelete = it })
                }
                if (archiveBusy) AlertDialog(onDismissRequest = {}, title = { Text("Processing CSV archive") },
                    text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
                archiveMessage?.let { message ->
                    AlertDialog(onDismissRequest = { archiveMessage = null }, title = { Text("CSV archive") },
                        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
                        confirmButton = { TextButton(onClick = { archiveMessage = null }) { Text("OK") } })
                }
                recordMessage?.let { message ->
                    AlertDialog(onDismissRequest = { recordMessage = null }, title = { Text("New historical records") },
                        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
                        confirmButton = { TextButton(onClick = { recordMessage = null }) { Text("OK") } })
                }
                pendingRecordDelete?.let { record ->
                    AlertDialog(onDismissRequest = { pendingRecordDelete = null }, title = { Text("Delete historical records?") },
                        text = { Text("Delete all gear records for ${record.identity}? Future CSV imports can create them again.") },
                        confirmButton = { TextButton(onClick = {
                            pendingRecordDelete = null
                            lifecycleScope.launch {
                                try {
                                    gearRecords = withContext(Dispatchers.IO) { gearRecordStore.delete(record) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, "Historical records could not be deleted.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { pendingRecordDelete = null }) { Text("Cancel") } })
                }
                if (sharedFiles.isNotEmpty()) AlertDialog(
                    onDismissRequest = { sharedFiles = emptyList() },
                    title = { Text("Choose a ride log") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            sharedFiles.forEach { (uri, name) ->
                                TextButton(onClick = { load(uri) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(name, style = RideTitleStyle)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { sharedFiles = emptyList() }) { Text("Cancel") } }
                )
            }
        }
        val restored = savedInstanceState?.getString("selected_uri")?.let(Uri::parse)
        val pending = savedInstanceState?.getStringArrayList("shared_uris")
        if (!pending.isNullOrEmpty()) {
            receive(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM, ArrayList(pending.map(Uri::parse))
            ))
        } else if (restored != null) load(restored) else receive(intent)
        showingHistory = savedInstanceState?.getBoolean("showing_history") ?: false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closeZoom()
        showingHistory = false
        receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCsvSave?.let {
            outState.putString("pending_csv_id", it.id)
            outState.putString("pending_csv_title", it.title)
        }
        outState.putBoolean("showing_history", showingHistory)
        zoomChannel?.let { outState.putString("zoom_channel", it.name) }
        selectedUri?.let { outState.putString("selected_uri", it.toString()) }
        if (sharedFiles.isNotEmpty()) outState.putStringArrayList("shared_uris", ArrayList(sharedFiles.map { it.first.toString() }))
        super.onSaveInstanceState(outState)
    }

    private fun receive(intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) return
        @Suppress("DEPRECATION")
        val streams = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        val clips = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        val files = (streams + clips).distinct()
        importJob?.cancel()
        sharedFiles = emptyList()
        loading = false
        error = null
        when (files.size) {
            0 -> error = "No CSV attachment was received. Use Open CSV to select a file."
            1 -> load(files.single())
            else -> {
                importJob = lifecycleScope.launch {
                    sharedFiles = withContext(Dispatchers.IO) {
                        files.map { uri ->
                            val name = runCatching {
                                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                }
                            }.getOrNull() ?: uri.lastPathSegment ?: "Ride log"
                            uri to name
                        }
                    }
                }
            }
        }
    }

    private fun load(uri: Uri) {
        showingHistory = false
        sharedFiles = emptyList()
        selectedUri = uri
        importJob?.cancel()
        importJob = lifecycleScope.launch {
            loading = true; error = null; summary = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("The selected file could not be opened.")
                    val sourceName = runCatching {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment
                    val savedTitle = historyEntries.firstOrNull { Uri.fromFile(historyStore.file(it)) == uri }?.title
                    val ride = RideAnalyzer.analyze(RideCsvParser.parse(bytes.inputStream(), sourceName, savedTitle))
                    ensureActive()
                    val saved = runCatching {
                        val entry = historyStore.record(bytes, ride.title)
                        entry to historyStore.entries()
                    }
                    val records = runCatching { gearRecordStore.record(ride) }
                    Triple(ride, saved, records)
                }
                summary = result.first
                result.third.onSuccess { update ->
                    gearRecords = update.records
                    if (update.broken.isNotEmpty()) recordMessage =
                        "${result.first.rider}@${result.first.bike}\n\n" + update.broken.joinToString("\n")
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Historical gear records could not be saved.", Toast.LENGTH_LONG).show()
                }
                result.second.onSuccess { (entry, entries) ->
                    historyEntries = entries
                    selectedUri = Uri.fromFile(historyStore.file(entry))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "The ride was opened, but could not be saved to CSV history.", Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = when (exception) {
                    is SecurityException -> "Access to this file was denied. Please select it again."
                    is IllegalArgumentException -> exception.message ?: "Invalid CSV file."
                    else -> "The file could not be read. Please select a valid RIDEOLOGY CSV file."
                }
            } finally {
                if (isActive) loading = false
            }
        }
    }

    private fun deleteHistoryEntry(entry: CsvHistoryEntry) {
        lifecycleScope.launch {
            try {
                importJob?.join()
                historyEntries = withContext(Dispatchers.IO) {
                    historyStore.delete(entry)
                    historyStore.entries()
                }
                if (historyEntries.isEmpty()) showingHistory = false
                if (selectedUri == Uri.fromFile(historyStore.file(entry))) {
                    selectedUri = null
                    summary = null
                    error = null
                    closeZoom()
                    setIntent(Intent(this@MainActivity, MainActivity::class.java))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "The saved CSV could not be deleted. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openZoom(channel: Channel) {
        zoomChannel = channel
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun closeZoom() {
        zoomChannel = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun openRideology() {
        val launch = listOf("jp.co.khi.mce.rideologytheappV2", "jp.co.khi.mce.rideologytheapp")
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        try {
            if (launch != null) startActivity(launch) else
                Toast.makeText(this, "RIDEOLOGY is not installed or cannot be opened.", Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "RIDEOLOGY could not be opened.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copySummary(ride: RideSummary) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(ride.title, RideReport.plainText(ride)))
        Toast.makeText(this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show()
    }

    private fun shareSummary(ride: RideSummary) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, ride.title)
            putExtra(Intent.EXTRA_TEXT, RideReport.messageText(ride))
        }
        try {
            startActivity(Intent.createChooser(sendIntent, "Share ride summary"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No compatible sharing application is installed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openMap(coordinate: Coordinate) {
        val query = "${coordinate.latitude},${coordinate.longitude}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$query?q=${Uri.encode(query)}")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No compatible map application is installed.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun RideScreen(
    summary: RideSummary?, loading: Boolean, error: String?, onOpen: (Uri) -> Unit,
    onMap: (Coordinate) -> Unit, onCopy: (RideSummary) -> Unit, onShare: (RideSummary) -> Unit,
    onRideology: () -> Unit, onZoom: (Channel) -> Unit, onHistory: () -> Unit,
    gearRecords: List<GearRecord>, onDeleteRecord: (GearRecord) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val appIcon = remember(resources, context.theme) {
        resources.getDrawable(R.mipmap.ic_launcher, context.theme).toBitmap(192, 192).asImageBitmap()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let(onOpen)
    }
    val scrollState = rememberScrollState()
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(end = 32.dp).verticalScroll(scrollState).padding(start = 20.dp, end = 4.dp, top = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(64.dp).testTag("app_icon"))
                    Text("RIDEOLOGY\nCOMPANION", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                }
                val authorLinkColor = MaterialTheme.colorScheme.primary
                Text(buildAnnotatedString {
                    append("Ride log analysis by ")
                    withLink(LinkAnnotation.Url("https://github.com/jbokser", TextLinkStyles(
                        style = SpanStyle(color = authorLinkColor, textDecoration = TextDecoration.Underline)
                    ))) { append("@jbokser") }
                    append(" · v${BuildConfig.VERSION_NAME}")
                }, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    val request = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    try { picker.launch(Intent.createChooser(request, "Open CSV with")) }
                    catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No compatible file browser is installed.", Toast.LENGTH_LONG).show()
                    }
                },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(if (summary == null) "Open CSV" else "Open another CSV", color = MaterialTheme.colorScheme.onSurface)
                }
                OutlinedButton(onClick = onHistory,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Previously loaded CSVs", color = MaterialTheme.colorScheme.onSurface)
                }
                OutlinedButton(onClick = onRideology,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Open Rideology app", color = MaterialTheme.colorScheme.onSurface)
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Processing ride log…")
                }
                if (error != null) Panel("Import error") { Text(error) }
                if (!loading && error == null && summary == null) Panel("Getting started") {
                    Text("Open a CSV exported by Kawasaki RIDEOLOGY, or share the file from RIDEOLOGY and select this app.")
                }
                summary?.let { ride ->
                    Text(ride.title, modifier = Modifier.padding(end = 12.dp), style = RideTitleStyle)
                    Panel("Ride summary", action = { ChartExportMenu(ride, ChartImageKind.SUMMARY, onCopy = { onCopy(ride) }, onShareText = { onShare(ride) }) }) {
                        RideReport.metrics(ride).forEach { Metric(it) }
                    }
                    var locationDetails by remember(ride) { mutableStateOf<Map<Coordinate, LocationDetails>>(emptyMap()) }
                    LaunchedEffect(ride) {
                        listOfNotNull(ride.start, ride.end, ride.maxSpeedLocation).distinct().forEach { coordinate ->
                            LocationLookup.lookup(context.applicationContext, coordinate)?.let { details ->
                                locationDetails = locationDetails + (coordinate to details)
                            }
                        }
                    }
                    Panel("Locations", action = {
                        TextExportMenu(ride.title, "Locations",
                            RideReport.locationsText(ride, locationDetails), RideReport.locationsText(ride, locationDetails, true))
                    }) {
                        Location("Starting point", ride.start, onMap, locationDetails[ride.start])
                        Location("Ending point", ride.end, onMap, locationDetails[ride.end])
                        Location("Maximum speed location", ride.maxSpeedLocation, onMap, locationDetails[ride.maxSpeedLocation])

                    }
                    ride.telemetry?.let { telemetry ->
                        Panel("Telemetry", action = { ChartExportMenu(ride, ChartImageKind.TELEMETRY) }) { TelemetryCharts(telemetry, onZoom) }
                    }
                    Panel("Max for each gear", action = { ChartExportMenu(ride, ChartImageKind.GEARS) }) {
                        com.jbokser.rideology_companion.ui.charts.GearMaximaTable(ride)
                    }

                    ride.telemetry?.let {
                        Panel("Speed distribution", action = { ChartExportMenu(ride, ChartImageKind.DISTRIBUTION) }) {
                            Column(Modifier.testTag("speed_distribution_section")) {
                                SpeedDistributionChart(ride.speedDistribution)
                            }
                        }
                    }
                    if (ride.warnings.isNotEmpty()) Panel("Data notes") { ride.warnings.forEach { Text(it) } }

                }
                gearRecords.forEach { record ->
                    val historicalRide = record.summary()
                    Panel("Historical max for each gear", action = {
                        Row {
                            ChartExportMenu(historicalRide, ChartImageKind.HISTORICAL_GEARS)
                            IconButton(onClick = { onDeleteRecord(record) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete records for ${record.identity}",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(R.drawable.ic_metric_helmet to record.rider, R.drawable.ic_metric_motorcycle to record.bike).forEach { (icon, name) ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(painterResource(icon), contentDescription = if (icon == R.drawable.ic_metric_helmet) "Rider" else "Bike",
                                        modifier = Modifier.size(18.dp))
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        com.jbokser.rideology_companion.ui.charts.GearMaximaTable(historicalRide)
                        Spacer(Modifier.height(12.dp))
                        Text("Theoretical max speed for each gear", style = MaterialTheme.typography.titleSmall)
                        Text(RideReport.theoreticalSubtitle(historicalRide), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        com.jbokser.rideology_companion.ui.charts.GearMaximaTable(historicalRide, theoretical = true)
                    }
                }
            }
            RideScrollbar(scrollState, Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight().padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun CsvHistoryScreen(
    entries: List<CsvHistoryEntry>, onBack: () -> Unit,
    onSelect: (CsvHistoryEntry) -> Unit, onDelete: (CsvHistoryEntry) -> Unit,
    onShare: (CsvHistoryEntry) -> Unit, onSave: (CsvHistoryEntry) -> Unit,
    onExportZip: () -> Unit, onShareZip: () -> Unit, onImportZip: () -> Unit, archiveBusy: Boolean
) {
    var pendingDelete by remember { mutableStateOf<CsvHistoryEntry?>(null) }
    val scrollState = rememberScrollState()
    BackHandler(onBack = onBack)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary)
                }
                Text("Previously loaded CSVs", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
                var archiveMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { archiveMenu = true }, enabled = !archiveBusy) {
                        Icon(painterResource(R.drawable.ic_archive), contentDescription = "CSV archive options",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = archiveMenu, onDismissRequest = { archiveMenu = false },
                        modifier = Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(2.dp)),
                        containerColor = MaterialTheme.colorScheme.surface) {
                        DropdownMenuItem(text = { Text("Export as ZIP") }, enabled = entries.isNotEmpty(), onClick = {
                            archiveMenu = false
                            onExportZip()
                        })
                        DropdownMenuItem(text = { Text("Share as ZIP") }, enabled = entries.isNotEmpty(), onClick = {
                            archiveMenu = false
                            onShareZip()
                        })
                        DropdownMenuItem(text = { Text("Import from ZIP") }, onClick = {
                            archiveMenu = false
                            onImportZip()
                        })
                    }
                }
            }
            HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(end = 32.dp).verticalScroll(scrollState).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) Text("No CSV files have been imported yet.")
                    entries.forEach { entry ->
                        key(entry.id) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                OutlinedButton(onClick = { onSelect(entry) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(2.dp)) {
                                    Text(entry.title, style = CsvHistoryTitleStyle, color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth())
                                }
                                var exportMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { exportMenu = true }) {
                                        Icon(painterResource(R.drawable.ic_share), contentDescription = "Share ${entry.title}",
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false },
                                        modifier = Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(2.dp)),
                                        containerColor = MaterialTheme.colorScheme.surface) {
                                        DropdownMenuItem(text = { Text("Share CSV") }, onClick = {
                                            exportMenu = false
                                            onShare(entry)
                                        })
                                        DropdownMenuItem(text = { Text("Save CSV") }, onClick = {
                                            exportMenu = false
                                            onSave(entry)
                                        })
                                    }
                                }
                                IconButton(onClick = { pendingDelete = entry }) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${entry.title}",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                RideScrollbar(scrollState, Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight().padding(vertical = 8.dp),
                    description = "Scroll previously loaded CSVs", tag = "csv_history_scrollbar")
            }
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved CSV?") },
            text = { Text("Remove “${entry.title}” from history and delete its saved copy?") },
            confirmButton = { TextButton(onClick = { pendingDelete = null; onDelete(entry) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Panel(title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            action?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Metric(metric: ReportMetric) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(com.jbokser.rideology_companion.ui.charts.metricIcon(metric)), contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface)
            Text(metric.label, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metric.value, style = MaterialTheme.typography.bodyLarge)
            metric.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Location(label: String, coordinate: Coordinate?, onMap: (Coordinate) -> Unit, details: LocationDetails? = null) {
    Text(label)
    if (coordinate == null) Text("Unavailable") else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(coordinate.display(), fontFamily = FontFamily.Monospace)
                details?.lines()?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { onMap(coordinate) }) {
                Icon(painterResource(R.drawable.ic_location_pin), contentDescription = "Open $label in maps",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RideScrollbar(state: ScrollState, modifier: Modifier, description: String = "Scroll ride report", tag: String = "ride_scrollbar") {
    if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE || state.viewportSize <= 0) return
    val color = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    Canvas(modifier.testTag(tag)
        .semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(state.value.toFloat(), 0f..state.maxValue.toFloat())
            setProgress { target ->
                scope.launch { state.scrollTo(target.toInt().coerceIn(0, state.maxValue)) }
                true
            }
        }
        .pointerInput(state) {
            var scrollJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val height = size.height.toFloat()
                val thumbHeight = scrollbarThumbHeight(state, height, 48.dp.toPx())
                val travel = height - thumbHeight
                val thumbTop = travel * state.value / state.maxValue.toFloat()
                val grabOffset = if (down.position.y in thumbTop..(thumbTop + thumbHeight)) {
                    down.position.y - thumbTop
                } else thumbHeight / 2f
                fun moveThumb(y: Float) {
                    if (travel <= 0f) return
                    val fraction = ((y - grabOffset) / travel).coerceIn(0f, 1f)
                    val target = (fraction * state.maxValue).toInt()
                    scrollJob?.cancel()
                    scrollJob = scope.launch { state.scrollTo(target) }
                }
                moveThumb(down.position.y)
                drag(down.id) { change ->
                    change.consume()
                    moveThumb(change.position.y)
                }
            }
        }
    ) {
        val thumbHeight = scrollbarThumbHeight(state, size.height, 48.dp.toPx())
        val thumbTop = (size.height - thumbHeight) * state.value / state.maxValue.toFloat()
        val barWidth = 12.dp.toPx()
        val left = size.width - barWidth - 8.dp.toPx()
        drawRect(color.copy(alpha = 0.2f), topLeft = Offset(left, 0f), size = Size(barWidth, size.height))
        drawRect(color, topLeft = Offset(left, thumbTop), size = Size(barWidth, thumbHeight))
    }
}

private fun scrollbarThumbHeight(state: ScrollState, height: Float, minimum: Float): Float {
    val contentHeight = state.viewportSize.toFloat() + state.maxValue
    return (height * state.viewportSize / contentHeight).coerceIn(minimum.coerceAtMost(height), height)
}
