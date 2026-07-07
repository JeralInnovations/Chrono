package com.chrono.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrono.app.ChronoViewModel
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.DeviceStatus
import com.chrono.app.ble.Proto
import com.chrono.app.data.Exporter
import com.chrono.app.data.TARGET_DIST_UNITS
import com.chrono.app.data.TestResult
import com.chrono.app.ui.theme.Amber
import com.chrono.app.ui.theme.Bad
import com.chrono.app.ui.theme.Good
import com.chrono.app.ui.theme.PanelHigh
import com.chrono.app.ui.theme.Teal
import com.chrono.app.ui.theme.TextDim
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReconnectingBanner() {
    val t = rememberInfiniteTransition(label = "recon")
    val a by t.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "reconAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bad.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.BluetoothDisabled, null,
            tint = Bad, modifier = Modifier.size(18.dp).alpha(a),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "Connection lost — reconnecting… results are safe on the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bad,
        )
    }
}

@Composable
fun DashboardScreen(vm: ChronoViewModel, connState: ConnState, deviceStatus: DeviceStatus?) {
    val state = deviceStatus?.state ?: -1
    val armed = state == Proto.ST_ARMED
    val running = state == Proto.ST_RUNNING
    var editing by remember { mutableStateOf<TestResult?>(null) }
    var manualEntry by remember { mutableStateOf(false) }
    // (photo uri, owning result uid) so the viewer can offer "set as cover"
    var fullscreenPhoto by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    var promptPhotoPreview by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedPromptPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedSetupPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = LocalContext.current
    val photoRevision = vm.photoRevision

    // System camera writing straight into the shot folder: no CAMERA permission.
    var pendingPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> pendingPhoto?.let { vm.photoSaved(ok, it) }; pendingPhoto = null }
    val pickPromptPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> vm.importPromptPhotos(uris) }

    // Manual-logging mode: no device, so hide everything device-specific.
    val offline = connState == ConnState.DISCONNECTED
    val setupPhotos = remember(photoRevision, vm.pendingLabel) { vm.setupPhotos() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar(vm, connState, deviceStatus) }

        if (connState == ConnState.RECONNECTING) {
            item { ReconnectingBanner() }
        }

        if (!offline) {
            item {
                OutlinedButton(
                    onClick = { vm.redoSetup() },
                    enabled = connState == ConnState.CONNECTED && !armed && !running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set up new test") }
            }
            if (vm.setupPhotosNeeded && vm.canAddSetupPhotos &&
                connState == ConnState.CONNECTED && !armed && !running
            ) {
                item {
                    Button(
                        onClick = { vm.requestSetupPhotos() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE00000),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Add setup photos")
                    }
                }
            }
        }

        if (vm.isSimulation) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Teal.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Simulation mode — no hardware connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Teal,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.simulateSignalLoss() }) {
                        Text(
                            if (connState == ConnState.RECONNECTING) "Restore signal"
                            else "Drop signal",
                            color = Teal,
                        )
                    }
                }
            }
        }

        if (offline) {
            item {
                Text(
                    "Manual logging — no chronograph connected. Entries, photos, " +
                        "and exports work; measuring needs the device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
            }
        } else {
            item { RigCard(vm, enabled = connState == ConnState.CONNECTED && !armed && !running) }

            item {
                ChannelsCard(
                    vm,
                    enabled = connState == ConnState.CONNECTED && !armed && !running && !vm.calRunning,
                )
            }

            item { NextTestCard(vm, armed = armed || running) }

            item {
                ArmButton(
                    armed = armed,
                    running = running,
                    connected = connState == ConnState.CONNECTED || connState == ConnState.RECONNECTING,
                    sensorsReady = vm.sensor1Ready && vm.sensor2Ready,
                    onArm = { vm.arm() },
                    onDisarm = { vm.disarm() },
                )
            }

            if (vm.canAddSetupPhotos && setupPhotos.isNotEmpty()) {
                item {
                    SetupPhotoStrip(
                        photos = setupPhotos,
                        selected = selectedSetupPhoto,
                        onOpen = { promptPhotoPreview = it },
                        onSelect = { selectedSetupPhoto = it },
                        onDelete = {
                            selectedSetupPhoto?.let { vm.deletePromptPhoto(it) }
                            selectedSetupPhoto = null
                        },
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { manualEntry = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.EditNote, null, tint = TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Log manual entry", color = TextDim)
            }
        }

        if (vm.results.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
                ) {
                    Text(
                        "RESULTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.openDataFolder() }) {
                        Icon(
                            Icons.Filled.FolderOpen, null,
                            tint = TextDim, modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Files", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { Exporter.export(context, vm.results.toList(), vm.isSimulation) }) {
                        Icon(
                            Icons.Filled.Share, null,
                            tint = TextDim, modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Export", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(vm.results, key = { it.uid }) { r ->
                ResultCard(
                    r,
                    latest = r == vm.results.firstOrNull(),
                    ciPercent = vm.ciPercentFor(r),
                    coverFor = { vm.photosFor(r) },
                    onEdit = { editing = r },
                    onOpenPhoto = { fullscreenPhoto = it to r.uid },
                )
            }
        }
    }

    // Sensor-attach flow: fit wire -> capacitance check -> tap test.
    // The input is deliberately NOT armed until the tap step, so movement
    // during placement can't trigger anything.
    vm.retestSensor?.let { sensor ->
        var step by remember(sensor) { mutableStateOf("attach") }
        val verified = state == if (sensor == 1) Proto.ST_VERIFY1_OK else Proto.ST_VERIFY2_OK
        AlertDialog(
            onDismissRequest = { vm.finishRetest(false) },
            title = { Text("Sensor $sensor — ${if (sensor == 1) "start" else "stop"}") },
            text = {
                Column {
                    when (step) {
                        "attach" -> Text(
                            "Fit the new sensor into port $sensor and route the wire. " +
                                "Nothing is armed yet — take your time. Confirm below " +
                                "when it's physically in place.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        "measure" -> {
                            if (vm.calRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = Amber,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("Checking capacitance…", color = TextDim)
                                }
                            } else {
                                val load = vm.channelLoadNs(sensor)
                                when {
                                    load == null -> Text(
                                        "Couldn't measure. Check the connection to the " +
                                            "device and try again.",
                                        color = Amber,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    load > 250 -> Text(
                                        "Sensor detected: capacitance is +%.2f us over the bare-port baseline (about %s). Looks attached."
                                            .format(load / 1000.0, vm.capacitanceText(load)),
                                        color = Good,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    else -> Text(
                                        "No capacitance increase over the bare-port " +
                                            "baseline — the sensor doesn't look attached. " +
                                            "Check the clips and re-measure.",
                                        color = Amber,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        else -> {
                            VerifyPane(
                                sensor = sensor,
                                deviceState = state,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (vm.isSimulation && !verified) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { vm.simulateTap() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Simulate sensor tap") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (step) {
                    "attach" -> TextButton(onClick = {
                        vm.startLoadedCal(sensor)
                        step = "measure"
                    }) { Text("Sensor attached") }
                    "measure" -> Row {
                        TextButton(
                            onClick = { vm.startLoadedCal(sensor) },
                            enabled = !vm.calRunning,
                        ) { Text("Re-measure") }
                        TextButton(
                            onClick = {
                                vm.beginTapTest(sensor)
                                step = "tap"
                            },
                            enabled = !vm.calRunning,
                        ) { Text("Continue") }
                    }
                    else -> TextButton(
                        onClick = { vm.finishRetest(true) },
                        enabled = verified,
                    ) { Text("Done") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.finishRetest(false) }) { Text("Cancel") }
            },
        )
    }

    // Shots received (e.g. after walking back into range): review, then photos.
    if (vm.newShots.isNotEmpty() && vm.retestSensor == null) {
        AlertDialog(
            onDismissRequest = { vm.dismissShotReview() },
            title = {
                Text(if (vm.newShots.size == 1) "Shot recorded" else "${vm.newShots.size} shots received")
            },
            text = {
                Column {
                    for (s in vm.newShots) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.1f".format(s.feetPerSecond),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 26.sp,
                                color = Amber,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("ft/s", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(12.dp))
                            Text(
                                s.splitTimeText(),
                                color = TextDim,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (s.label.isNotBlank()) {
                            Text(s.label, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissShotReview() }) { Text("Continue") }
            },
        )
    }

    vm.resultPrompt?.let { r ->
        EditResultDialog(
            result = r,
            title = "Log result",
            dismissText = "Close",
            showDelete = false,
            showRecordedValues = true,
            onDismiss = { vm.finishResultPrompt() },
            onSave = { label, tool, target, tdVal, tdUnit, outcome, epochMillis ->
                vm.updateResult(r.uid, label, tool, target, tdVal, tdUnit, outcome, epochMillis)
                vm.finishResultPrompt()
            },
            onAttachPhotos = { uris -> vm.attachPhotosToResult(r.uid, uris) },
            photosFor = { vm.photosFor(r) },
            onOpenPhoto = { fullscreenPhoto = it to r.uid },
            onDeletePhoto = { uri -> vm.deleteResultPhoto(r.uid, uri) },
            onDelete = {},
        )
    }

    editing?.let { r ->
        EditResultDialog(
            result = r,
            onDismiss = { editing = null },
            onSave = { label, tool, target, tdVal, tdUnit, outcome, epochMillis ->
                vm.updateResult(r.uid, label, tool, target, tdVal, tdUnit, outcome, epochMillis)
                editing = null
            },
            onAttachPhotos = { uris -> vm.attachPhotosToResult(r.uid, uris) },
            photosFor = { vm.photosFor(r) },
            onOpenPhoto = { fullscreenPhoto = it to r.uid },
            onDeletePhoto = { uri -> vm.deleteResultPhoto(r.uid, uri) },
            onDelete = {
                vm.deleteResult(r.uid)
                editing = null
            },
        )
    }

    if (vm.showFullLog) {
        FullLogDialog(
            vm = vm,
            onExit = { vm.hideFullLog() },
            onEdit = {
                vm.hideFullLog()
                editing = it
            },
            onOpenPhoto = { uri, uid -> fullscreenPhoto = uri to uid },
        )
    }

    fullscreenPhoto?.let { (uri, uid) ->
        Dialog(
            onDismissRequest = { fullscreenPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable { fullscreenPhoto = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OutlinedButton(onClick = {
                        vm.setResultThumbnail(uid, uri.toString())
                        fullscreenPhoto = null
                    }) { Text("Set as shot thumbnail") }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap image to close",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }

    if (manualEntry) {
        ManualEntryDialog(
            vm = vm,
            onDismiss = { manualEntry = false },
            onSave = { label, tool, target, tdVal, tdUnit, outcome, vel, velFps, epoch, photos ->
                vm.addManualEntry(
                    label, tool, target, tdVal, tdUnit, outcome, vel, velFps, epoch, photos,
                )
                manualEntry = false
            },
        )
    }

    // Photo prompts: after setup, and after each recorded shot.
    vm.photoPrompt?.let { kind ->
        val promptPhotos = remember(photoRevision, kind, vm.pendingLabel) { vm.promptPhotos(kind) }
        val savedCount = promptPhotos.size
        AlertDialog(
            onDismissRequest = { vm.dismissPhotoPrompt() },
            title = { Text(if (kind == "setup") "Setup photos" else "After photos") },
            text = {
                Column {
                    Text(
                        if (kind == "setup")
                            "Photograph your rig as it stands for this test — sensor " +
                                "placement, spacing, tool. Saved to this shot's folder."
                        else
                            "Photograph the target and anything notable about the " +
                                "outcome. Saved with this shot's log.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (savedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "$savedCount photo${if (savedCount == 1) "" else "s"} saved",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Good,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(promptPhotos) { uri ->
                                PhotoThumbnail(
                                    uri = uri,
                                    selected = selectedPromptPhoto == uri,
                                    onOpen = { promptPhotoPreview = uri },
                                    onSelect = { selectedPromptPhoto = uri },
                                )
                            }
                        }
                        if (selectedPromptPhoto != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                selectedPromptPhoto?.let { vm.deletePromptPhoto(it) }
                                selectedPromptPhoto = null
                            }) {
                                Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Delete selected", color = Bad)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { pickPromptPhotos.launch("image/*") }) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Upload")
                    }
                    TextButton(onClick = {
                        vm.newPhotoUri()?.let { uri ->
                            pendingPhoto = uri
                            runCatching { takePicture.launch(uri) }
                        }
                    }) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Take Photo")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPhotoPrompt() }) {
                    Text(if (savedCount == 0) "Skip" else "Done")
                }
            },
        )
    }

    promptPhotoPreview?.let { uri ->
        Dialog(
            onDismissRequest = { promptPhotoPreview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .clickable { promptPhotoPreview = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Tap image to close",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupPhotoStrip(
    photos: List<android.net.Uri>,
    selected: android.net.Uri?,
    onOpen: (android.net.Uri) -> Unit,
    onSelect: (android.net.Uri) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Setup photos",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.weight(1f),
            )
            if (selected != null) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Delete", color = Bad)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos) { uri ->
                PhotoThumbnail(
                    uri = uri,
                    selected = selected == uri,
                    onOpen = { onOpen(uri) },
                    onSelect = { onSelect(uri) },
                )
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(
    uri: android.net.Uri,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = uri,
        contentDescription = "saved photo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) Bad else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { onSelect() },
                )
            },
    )
}

@Composable
private fun FullLogDialog(
    vm: ChronoViewModel,
    onExit: () -> Unit,
    onEdit: (TestResult) -> Unit,
    onOpenPhoto: (android.net.Uri, String) -> Unit,
) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Test log", style = MaterialTheme.typography.headlineMedium, color = Amber)
                    Text(
                        "${vm.results.size} result${if (vm.results.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                }
                TextButton(onClick = onExit) { Text("Exit") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.results, key = { it.uid }) { r ->
                    Column {
                        ResultCard(
                            r = r,
                            latest = r == vm.results.firstOrNull(),
                            ciPercent = vm.ciPercentFor(r),
                            coverFor = { vm.photosFor(r) },
                            onEdit = { onEdit(r) },
                            onOpenPhoto = { onOpenPhoto(it, r.uid) },
                        )
                        val photos = vm.photosFor(r)
                        if (photos.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(photos) { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "shot photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onOpenPhoto(uri, r.uid) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: ChronoViewModel, connState: ConnState, deviceStatus: DeviceStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "JERAL INNOVATIONS",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHRONO", style = MaterialTheme.typography.headlineMedium, color = Amber)
                if (vm.isSimulation) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "SIM",
                        style = MaterialTheme.typography.labelSmall,
                        color = Teal,
                        modifier = Modifier
                            .background(Teal.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint, label) = when (connState) {
                    ConnState.CONNECTED ->
                        Triple(Icons.Filled.BluetoothConnected, Good, "Connected")
                    ConnState.RECONNECTING ->
                        Triple(Icons.Filled.BluetoothDisabled, Bad, "Reconnecting")
                    else -> Triple(Icons.Filled.BluetoothDisabled, TextDim, "Disconnected")
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(5.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
                Spacer(Modifier.size(14.dp))
                val timeOk = deviceStatus?.timeValid == true
                Icon(
                    Icons.Filled.AccessTime, null,
                    tint = if (timeOk) Teal else TextDim,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { vm.syncTime() },
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    if (timeOk) "Clock synced" else "Clock not set — tap to sync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (timeOk) Teal else TextDim,
                    modifier = Modifier.clickable { vm.syncTime() },
                )
            }
            vm.projectName?.let {
                Text(
                    "${vm.session.pathLabel} — tap to open",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    modifier = Modifier.clickable { vm.openDataFolder() },
                )
            }
        }
        TextButton(onClick = { vm.disconnectOrExit() }) {
            Text(
                if (connState == ConnState.DISCONNECTED) "Exit" else "Disconnect",
                color = TextDim,
            )
        }
    }
}

/**
 * The rig, drawn the way it stands on the bench: sensor 1 up front, the
 * measured gap, sensor 2 behind it. Tap a sensor to retest/replace it; tap
 * the distance to change it. Screens consumed by a shot show torn + amber.
 */
@Composable
private fun RigCard(vm: ChronoViewModel, enabled: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                "SHOT DIRECTION  ▸",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SensorGraphic(1, vm.sensor1Ready, enabled) { vm.startRetest(1) }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpanArrow()
                    TextButton(onClick = { vm.changeDistance() }, enabled = enabled) {
                        Text(
                            "${trimZeros(vm.distanceInUnit())} ${vm.distanceUnit.label}",
                            color = Teal,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "tap to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDim,
                    )
                    vm.estimatedCiAtCurrentSpacing()?.let { ci ->
                        Text(
                            "≈ ±%.1f%% CI here".format(ci),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ci > 2.0) Amber else TextDim,
                        )
                    }
                }
                SensorGraphic(2, vm.sensor2Ready, enabled) { vm.startRetest(2) }
            }
            if (!vm.sensor1Ready || !vm.sensor2Ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Each shot consumes the screens. Tap a torn sensor to connect " +
                        "and verify its replacement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Amber,
                )
            }
        }
    }
}

@Composable
private fun SensorGraphic(number: Int, ready: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val frame = if (ready) Good else Amber
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled, onClick = onTap),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp, 80.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(color = PanelHigh, cornerRadius = CornerRadius(10f, 10f))
                // screen mesh
                for (i in 1..3) {
                    val x = w * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 6f), Offset(x, h - 6f), 2f)
                    val y = h * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(6f, y), Offset(w - 6f, y), 2f)
                }
                drawRoundRect(
                    color = frame,
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 5f),
                )
                if (!ready) {
                    // jagged tear through a consumed screen
                    val tear = Path().apply {
                        moveTo(w * 0.55f, 0f)
                        lineTo(w * 0.35f, h * 0.30f)
                        lineTo(w * 0.62f, h * 0.46f)
                        lineTo(w * 0.40f, h * 0.72f)
                        lineTo(w * 0.55f, h)
                    }
                    drawPath(tear, Amber, style = Stroke(width = 4f))
                }
            }
            Text(
                "$number",
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = frame,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (number == 1) "START" else "STOP",
            style = MaterialTheme.typography.labelSmall,
            color = TextDim,
        )
        Text(
            if (ready) "Ready" else "Connect new\nsensor",
            style = MaterialTheme.typography.bodyMedium,
            color = frame,
            textAlign = TextAlign.Center,
        )
    }
}

/** A |◀——▶| measurement arrow spanning the gap between the two screens. */
@Composable
private fun SpanArrow() {
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val w = size.width
        val cy = size.height / 2f
        drawLine(TextDim, Offset(2f, 3f), Offset(2f, size.height - 3f), 3f)
        drawLine(TextDim, Offset(w - 2f, 3f), Offset(w - 2f, size.height - 3f), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(w - 2f, cy), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(12f, cy - 5f), 3f)
        drawLine(TextDim, Offset(2f, cy), Offset(12f, cy + 5f), 3f)
        drawLine(TextDim, Offset(w - 2f, cy), Offset(w - 12f, cy - 5f), 3f)
        drawLine(TextDim, Offset(w - 2f, cy), Offset(w - 12f, cy + 5f), 3f)
    }
}

@Composable
private fun ChannelsCard(vm: ChronoViewModel, enabled: Boolean) {
    val load1 = vm.channelLoadNs(1)
    val load2 = vm.channelLoadNs(2)
    val mismatch = vm.channelMismatchNs()
    val hasBaseline = vm.calData.containsKey("b1") && vm.calData.containsKey("b2")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Channel calibration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (vm.calRunning) {
                    CircularProgressIndicator(
                        color = Amber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    )
                } else {
                    OutlinedButton(
                        onClick = { vm.recalibrateChannels() },
                        enabled = enabled && hasBaseline,
                    ) { Text("Recheck") }
                }
            }
            Spacer(Modifier.height(10.dp))
            for ((ch, load) in listOf(1 to load1, 2 to load2)) {
                Text(
                    if (load != null)
                        "Port $ch load:  +%.2f us  (about %s)".format(load / 1000.0, vm.capacitanceText(load))
                    else "Port $ch load:  — not measured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            when {
                !hasBaseline -> Text(
                    "No baseline captured — redo setup with empty ports to enable.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                mismatch == null -> Text(
                    "Plug in and measure both sensors to compare channels.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                mismatch < 600 -> Text(
                    "Channels matched  (Δ $mismatch ns — timing impact negligible)",
                    style = MaterialTheme.typography.bodyMedium, color = Good,
                )
                else -> Text(
                    "Channel mismatch delta $mismatch ns (about ${vm.capacitanceText(mismatch)}) - " +
                        "check that both cables are the same length and type.",
                    style = MaterialTheme.typography.bodyMedium, color = Amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            val hw by vm.ble.hwInfo.collectAsState()
            Text(
                hw?.let {
                    val serial = it.mcuSerial.ifBlank { "serial unavailable" }
                    "Hardware rev ${it.hwRev} - fw ${it.fwMajor}.${it.fwMinor} - " +
                        "%.1f ns timer - $serial".format(it.tickPs / 1000.0)
                } ?: "Hardware not identified - assuming rev 1 accuracy",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
        }
    }
}

@Composable
private fun NextTestCard(vm: ChronoViewModel, armed: Boolean) {
    val fieldText = MaterialTheme.typography.bodyMedium
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("Next test", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Applied to the next result; everything is editable afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextDim,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = vm.pendingLabel,
                onValueChange = { vm.pendingLabel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Label") },
                placeholder = { Text("20 degree upward angle, (any special notes)", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingTool,
                onValueChange = { vm.pendingTool = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tool") },
                placeholder = { Text("PAN BK40, Hydrajet C2, etc.", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingTarget,
                onValueChange = { vm.pendingTarget = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Target") },
                placeholder = { Text("Ammo Can etc.", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.pendingTargetDistVal,
                onValueChange = { vm.pendingTargetDistVal = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Distance to target") },
                placeholder = { Text("25", color = TextDim) },
                textStyle = fieldText,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    UnitSelector(vm.pendingTargetDistUnit) { vm.pendingTargetDistUnit = it }
                },
            )
        }
    }
}

@Composable
private fun ArmButton(
    armed: Boolean,
    running: Boolean,
    connected: Boolean,
    sensorsReady: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "arm")
    val pulse by t.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "armPulse",
    )

    when {
        running -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .border(2.dp, Color(0xFFE00000), RoundedCornerShape(20.dp))
                    .background(
                        Color(0xFFE00000).copy(alpha = 0.18f + 0.20f * pulse),
                        RoundedCornerShape(20.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "RECORDING - SHOT IN FLIGHT",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
        armed -> {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .border(2.dp, Color(0xFFE00000), RoundedCornerShape(20.dp))
                        .background(
                            Color(0xFFE00000).copy(alpha = 0.14f + 0.24f * pulse),
                            RoundedCornerShape(20.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "STANDBY - WAITING FOR SHOT",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "You can walk away; the result uploads when you're back in range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onDisarm, modifier = Modifier.fillMaxWidth()) {
                    Text("Disarm")
                }
            }
        }
        else -> {
            Column {
                Button(
                    onClick = onArm,
                    enabled = connected && sensorsReady,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE00000),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE00000).copy(alpha = 0.28f),
                        disabledContentColor = Color.White.copy(alpha = 0.55f),
                    ),
                ) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        drawCircle(Color.White)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("RECORD", style = MaterialTheme.typography.labelLarge)
                }
                if (connected && !sensorsReady) {
                    Text(
                        "Replace and retest both sensors before recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    r: TestResult,
    latest: Boolean,
    ciPercent: Double,
    coverFor: () -> List<android.net.Uri>,
    onEdit: () -> Unit,
    onOpenPhoto: (android.net.Uri) -> Unit,
) {
    // Resolve the cover image off the main thread: chosen thumbnail, else first photo.
    val cover by androidx.compose.runtime.produceState<android.net.Uri?>(
        null, r.uid, r.thumbnailUri, r.shotFolder,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            r.thumbnailUri.takeIf { it.isNotBlank() }
                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                ?: coverFor().firstOrNull()
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (latest) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.label.ifBlank { "Untitled test" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.label.isBlank()) TextDim else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, "Edit", tint = TextDim, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (r.metersPerSecond > 0) "%.1f".format(r.feetPerSecond) else "—",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 36.sp,
                        color = Amber,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("ft/s", color = TextDim, modifier = Modifier.padding(bottom = 5.dp))
                }
                Spacer(Modifier.height(4.dp))
                val ciText = if (ciPercent >= 0.05) "±%.1f%% CI".format(ciPercent) else "±<0.1% CI"
                val detail = when {
                    r.isManual && r.metersPerSecond > 0 ->
                        "%.2f m/s  ·  manual entry".format(r.metersPerSecond)
                    r.isManual -> "manual entry"
                    else -> "%.2f m/s  -  %s  -  %s".format(
                        r.metersPerSecond, r.splitTimeText(), ciText
                    )
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.isManual) Teal else TextDim,
                )
                val meta = listOfNotNull(
                    r.tool.takeIf { it.isNotBlank() }?.let { "Tool: $it" },
                    r.target.takeIf { it.isNotBlank() }?.let { "Target: $it" },
                    r.targetDistanceText()?.let { "@ $it" },
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                }
                if (r.outcome.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Result: ${r.outcome}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val date = r.formattedDate()
                Text(
                    date ?: "No date — tap ✎ to set one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (date != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    else TextDim.copy(alpha = 0.55f),
                )
            }
            cover?.let { uri ->
                Spacer(Modifier.size(14.dp))
                AsyncImage(
                    model = uri,
                    contentDescription = "shot photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenPhoto(uri) },
                )
            }
        }
    }
}

private val EDIT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Compact tap-to-open unit picker, used as a trailing icon inside fields. */
@Composable
private fun UnitSelector(unit: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            unit,
            color = Teal,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TARGET_DIST_UNITS.forEach { u ->
                DropdownMenuItem(text = { Text(u) }, onClick = { onSelect(u); open = false })
            }
        }
    }
}

/**
 * Date/time entry that helps instead of nagging: clicking the empty box
 * autofills the current date and pre-selects the time digits so the user can
 * immediately type over them.
 */
@Composable
private fun DateTimeField(
    field: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    isError: Boolean,
    supporting: String?,
) {
    OutlinedTextField(
        value = field,
        onValueChange = onChange,
        label = { Text("Date & time") },
        placeholder = { Text("tap to fill current time", color = TextDim) },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        isError = isError,
        supportingText = {
            if (isError) Text("Use format 2026-07-05 14:30")
            else if (supporting != null) Text(supporting, color = TextDim)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { st ->
                if (st.isFocused && field.text.isBlank()) {
                    val now = EDIT_DATE_FORMAT.format(LocalDateTime.now())
                    onChange(TextFieldValue(now, selection = TextRange(11, 16)))
                }
            },
    )
}

private fun parseDateField(text: String): Long? =
    text.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching {
            LocalDateTime.parse(it, EDIT_DATE_FORMAT)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

@Composable
private fun ReadOnlyLogValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun EditResultDialog(
    result: TestResult,
    title: String = "Edit test",
    dismissText: String = "Cancel",
    showDelete: Boolean = true,
    showRecordedValues: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (
        label: String, tool: String, target: String,
        targetDistValue: Double?, targetDistUnit: String,
        outcome: String, epochMillis: Long?,
    ) -> Unit,
    onAttachPhotos: (List<android.net.Uri>) -> Unit,
    photosFor: () -> List<android.net.Uri>,
    onOpenPhoto: (android.net.Uri) -> Unit,
    onDeletePhoto: (android.net.Uri) -> Unit,
    onDelete: () -> Unit,
) {
    var photoRefresh by remember { mutableStateOf(0) }
    val photos = remember(photoRefresh) { photosFor() }
    var selectedPhoto by remember(photoRefresh) { mutableStateOf<android.net.Uri?>(null) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAttachPhotos(uris)
            photoRefresh++
        }
    }
    var label by remember { mutableStateOf(result.label) }
    var tool by remember { mutableStateOf(result.tool) }
    var target by remember { mutableStateOf(result.target) }
    var tdVal by remember { mutableStateOf(result.targetDistValue?.let { trimZeros(it) } ?: "") }
    var tdUnit by remember { mutableStateOf(result.targetDistUnit) }
    var outcome by remember { mutableStateOf(result.outcome) }
    var dateField by remember {
        mutableStateOf(
            TextFieldValue(
                result.epochMillis?.let {
                    EDIT_DATE_FORMAT.format(
                        LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()
                        )
                    )
                } ?: ""
            )
        )
    }
    val parsedDate = parseDateField(dateField.text)
    val dateError = dateField.text.isNotBlank() && parsedDate == null
    val fieldText = MaterialTheme.typography.bodyMedium

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (showRecordedValues) {
                    val spacingIn = result.distanceM * 39.3701
                    val velocityText = if (result.metersPerSecond > 0)
                        "%.1f ft/s".format(result.feetPerSecond) else "Not recorded"
                    ReadOnlyLogValue("Velocity", velocityText)
                    ReadOnlyLogValue("Sensor spacing", "%.3f in".format(spacingIn))
                    ReadOnlyLogValue("Split time", result.splitTimeText())
                    if (result.deviceSerial.isNotBlank()) {
                        ReadOnlyLogValue("MCU serial", result.deviceSerial)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("20 degree upward angle, (any special notes)", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tool,
                    onValueChange = { tool = it },
                    label = { Text("Tool") },
                    placeholder = { Text("PAN BK40, Hydrajet C2, etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target") },
                    placeholder = { Text("Ammo Can etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tdVal,
                    onValueChange = { tdVal = it },
                    label = { Text("Distance to target") },
                    textStyle = fieldText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = { UnitSelector(tdUnit) { tdUnit = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outcome,
                    onValueChange = { outcome = it },
                    label = { Text("Result") },
                    placeholder = { Text("penetration, depth, dent etc.", color = TextDim) },
                    textStyle = fieldText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (showRecordedValues && outcome.isBlank()) Amber.copy(alpha = 0.14f)
                            else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        ),
                )
                Spacer(Modifier.height(8.dp))
                DateTimeField(
                    field = dateField,
                    onChange = { dateField = it },
                    isError = dateError,
                    supporting = if (result.epochMillis == null && dateField.text.isBlank())
                        "Device clock wasn't synced for this test" else null,
                )
                if (photos.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Photos", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photos) { uri ->
                            PhotoThumbnail(
                                uri = uri,
                                selected = selectedPhoto == uri,
                                onOpen = { onOpenPhoto(uri) },
                                onSelect = { selectedPhoto = uri },
                            )
                        }
                    }
                    if (selectedPhoto != null) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            selectedPhoto?.let { onDeletePhoto(it) }
                            selectedPhoto = null
                            photoRefresh++
                        }) {
                            Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Delete selected photo", color = Bad)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { pickImages.launch("image/*") }) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add photos", color = Teal)
                }
                if (showDelete) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, null, tint = Bad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Delete this result", color = Bad)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(), tool.trim(), target.trim(),
                        tdVal.replace(',', '.').toDoubleOrNull(), tdUnit,
                        outcome.trim(), parsedDate,
                    )
                },
                enabled = !dateError,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

@Composable
private fun ManualEntryDialog(
    vm: ChronoViewModel,
    onDismiss: () -> Unit,
    onSave: (
        label: String, tool: String, target: String,
        targetDistValue: Double?, targetDistUnit: String, outcome: String,
        velocity: Double?, velocityIsFps: Boolean, epochMillis: Long?,
        photos: List<android.net.Uri>,
    ) -> Unit,
) {
    var pickedPhotos by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> pickedPhotos = pickedPhotos + uris }
    var label by remember { mutableStateOf(vm.pendingLabel) }
    var tool by remember { mutableStateOf(vm.pendingTool) }
    var target by remember { mutableStateOf(vm.pendingTarget) }
    var tdVal by remember { mutableStateOf(vm.pendingTargetDistVal) }
    var tdUnit by remember { mutableStateOf(vm.pendingTargetDistUnit) }
    var outcome by remember { mutableStateOf("") }
    var velText by remember { mutableStateOf("") }
    var velIsFps by remember { mutableStateOf(true) }
    var velUnitOpen by remember { mutableStateOf(false) }
    var dateField by remember {
        mutableStateOf(
            TextFieldValue(
                EDIT_DATE_FORMAT.format(LocalDateTime.now()),
                selection = TextRange(11, 16),
            )
        )
    }
    val parsedDate = parseDateField(dateField.text)
    val dateError = dateField.text.isNotBlank() && parsedDate == null
    val fieldText = MaterialTheme.typography.bodyMedium

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Log a shot without a connected chronograph. Velocity is " +
                        "optional — leave it blank for a notes/photos-only entry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = velText,
                    onValueChange = { velText = it },
                    label = { Text("Velocity (optional)") },
                    textStyle = fieldText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        Box {
                            TextButton(onClick = { velUnitOpen = true }) {
                                Text(if (velIsFps) "ft/s" else "m/s", color = Teal)
                            }
                            DropdownMenu(
                                expanded = velUnitOpen,
                                onDismissRequest = { velUnitOpen = false },
                            ) {
                                DropdownMenuItem(text = { Text("ft/s") },
                                    onClick = { velIsFps = true; velUnitOpen = false })
                                DropdownMenuItem(text = { Text("m/s") },
                                    onClick = { velIsFps = false; velUnitOpen = false })
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("20 degree upward angle, (any special notes)", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tool,
                    onValueChange = { tool = it },
                    label = { Text("Tool") },
                    placeholder = { Text("PAN BK40, Hydrajet C2, etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target") },
                    placeholder = { Text("Ammo Can etc.", color = TextDim) },
                    textStyle = fieldText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tdVal,
                    onValueChange = { tdVal = it },
                    label = { Text("Distance to target") },
                    textStyle = fieldText,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = { UnitSelector(tdUnit) { tdUnit = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outcome,
                    onValueChange = { outcome = it },
                    label = { Text("Result") },
                    placeholder = { Text("penetration, depth, dent etc.", color = TextDim) },
                    textStyle = fieldText,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                DateTimeField(
                    field = dateField,
                    onChange = { dateField = it },
                    isError = dateError,
                    supporting = null,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { pickImages.launch("image/*") }) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (pickedPhotos.isEmpty()) "Add photos"
                        else "Add photos (${pickedPhotos.size} selected)",
                        color = Teal,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(), tool.trim(), target.trim(),
                        tdVal.replace(',', '.').toDoubleOrNull(), tdUnit, outcome.trim(),
                        velText.replace(',', '.').toDoubleOrNull(), velIsFps, parsedDate,
                        pickedPhotos,
                    )
                },
                enabled = !dateError,
            ) { Text("Save entry") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
