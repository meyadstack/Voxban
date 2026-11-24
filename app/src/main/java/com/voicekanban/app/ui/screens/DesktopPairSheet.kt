package com.voicekanban.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.voicekanban.app.sync.DesktopSyncManager
import com.voicekanban.app.sync.PairingPayload
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.ToastKind
import com.voicekanban.app.ui.components.SectionLabel
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private enum class PairTab { SCAN, MANUAL }

/** Compact Settings card for linking & syncing with Voxban Windows PC. */
@Composable
fun DesktopSyncSettingsPanel(vm: MainViewModel, onOpenPairSheet: () -> Unit) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    val paired = app.settings.pairedDesktop

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.limeTile), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Computer, null, tint = c.onLime, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Voxban Desktop (Win / Mac)", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(
                    if (paired != null) "Paired with ${paired.pcName} (${paired.host})"
                    else "Scan QR code on your Windows PC or Mac to link & sync",
                    fontSize = 11.sp,
                    color = c.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Button(
            onClick = onOpenPairSheet,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (paired != null) "Manage Desktop Sync & QR" else "Scan Desktop QR Code")
        }
    }
}

/**
 * Bottom sheet with live CameraX + ZXing QR code scanner and manual code entry
 * to link and sync Voxban Android with the Voxban Windows PC app over Wi-Fi/LAN.
 */
@Composable
fun DesktopPairSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app by vm.state.collectAsState()
    val paired = app.settings.pairedDesktop

    var tab by rememberSaveable { mutableStateOf(PairTab.SCAN) }
    var hostInput by rememberSaveable { mutableStateOf(paired?.let { "${it.host}:${it.port}" } ?: "") }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var hasCameraPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPerm = granted
        if (!granted) status = false to "Camera permission is needed to scan QR codes (or use Manual Code tab)."
    }

    fun executePair(payload: PairingPayload) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            val res = DesktopSyncManager.pairWithDesktop(payload, vm.state.value)
            res.onSuccess { out ->
                vm.applyDesktopSync(out.desktop, out.mergedState)
                status = true to "Linked with ${out.desktop.pcName}! Your tasks, notes, calendar & workouts are synced."
                vm.showToast(ToastKind.SUCCESS, "Linked with ${out.desktop.pcName}")
            }.onFailure { err ->
                status = false to (err.message ?: "Couldn’t reach Windows PC. Make sure phone & PC are on the same Wi-Fi.")
            }
            busy = false
        }
    }

    AppSheet(
        title = "Link Desktop (PC / Mac)",
        subtitle = "Scan the QR code in Voxban Desktop on the same Wi-Fi",
        onDismiss = onDismiss,
    ) {
        // Connected PC status card
        if (paired != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(c.lowSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Computer, null, tint = c.low, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(paired.pcName, style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Text(
                            "${paired.host}:${paired.port}" + if (paired.lastSyncedAt > 0L) " · synced ${relativeTime(paired.lastSyncedAt)}" else "",
                            fontSize = 11.sp,
                            color = c.inkSecondary,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true; status = null
                            scope.launch {
                                DesktopSyncManager.syncWithDesktop(paired, vm.state.value)
                                    .onSuccess { merged ->
                                        vm.applyDesktopSync(paired.copy(lastSyncedAt = System.currentTimeMillis()), merged)
                                        status = true to "Synced with ${paired.pcName}!"
                                    }
                                    .onFailure { err ->
                                        status = false to (err.message ?: "Sync failed. Check that Voxban PC is open on the same Wi-Fi.")
                                    }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onLime)
                        else Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync Now")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.updateSettings { it.copy(pairedDesktop = null) }
                            status = true to "Disconnected from ${paired.pcName}."
                        },
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.LinkOff, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unlink")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Status feedback banner
        status?.let { (ok, msg) ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (ok) c.lowSoft else c.dangerSoft).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    null,
                    tint = if (ok) c.low else c.danger,
                    modifier = Modifier.size(18.dp),
                )
                Text(msg, fontSize = 12.sp, color = if (ok) c.ink else c.danger)
            }
        }

        // Tabs: Scan QR | Manual Code
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Scan QR", tab == PairTab.SCAN, Modifier.weight(1f), Icons.Rounded.QrCodeScanner) { tab = PairTab.SCAN }
            Pill("Manual Code", tab == PairTab.MANUAL, Modifier.weight(1f), Icons.Rounded.Keyboard) { tab = PairTab.MANUAL }
        }

        Spacer(Modifier.height(12.dp))

        when (tab) {
            PairTab.SCAN -> {
                if (!hasCameraPerm) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, null, tint = c.accent, modifier = Modifier.size(40.dp))
                        Text("Allow camera to scan PC QR", style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center)
                        Text(
                            "Open Voxban on your Windows PC, then point your phone camera at the QR code to link instantly.",
                            fontSize = 12.sp,
                            color = c.inkSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                        ) { Text("Allow Camera") }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(22.dp))
                            .background(Color.Black).border(2.dp, c.limeTile, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrCameraPreview(
                            enabled = !busy,
                            onQrDetected = { raw ->
                                val parsed = DesktopSyncManager.parseQrPayload(raw)
                                if (parsed != null) {
                                    executePair(parsed)
                                } else {
                                    status = false to "That QR code isn’t a Voxban PC pairing code."
                                }
                            },
                        )
                        // Subtle targeting reticle
                        Box(
                            Modifier.size(190.dp).border(2.5.dp, c.limeTile.copy(alpha = 0.85f), RoundedCornerShape(24.dp)),
                        )
                        if (busy) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = c.limeTile)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Connecting to Windows PC…", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Text(
                        "Point at the QR code displayed on Voxban for Windows PC.",
                        fontSize = 12.sp,
                        color = c.inkSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            PairTab.MANUAL -> {
                SectionLabel("PC address (or paste QR link)")
                InputField(
                    value = hostInput,
                    onValue = { hostInput = it; status = null },
                    placeholder = "e.g. 192.168.1.42:43821",
                )
                SectionLabel("6-digit pairing code (from Windows PC)")
                InputField(
                    value = codeInput,
                    onValue = { codeInput = it.filter { ch -> ch.isLetterOrDigit() }.uppercase().take(8); status = null },
                    placeholder = "e.g. 482910",
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val trimmed = hostInput.trim()
                        // Allow pasting the full QR URL directly too!
                        val fromUrl = DesktopSyncManager.parseQrPayload(trimmed)
                        if (fromUrl != null) {
                            executePair(fromUrl)
                            return@Button
                        }
                        if (trimmed.isEmpty() || codeInput.trim().length < 4) {
                            status = false to "Enter your PC address (e.g. 192.168.1.42:43821) and pairing code."
                            return@Button
                        }
                        val cleanHost = trimmed.removePrefix("http://").removePrefix("https://").substringBefore("/")
                        val host = cleanHost.substringBefore(":")
                        val port = cleanHost.substringAfter(":", "43821").toIntOrNull() ?: 43821
                        executePair(PairingPayload(host = host, port = port, token = "", pcName = "Voxban PC", code = codeInput.trim()))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onLime)
                    else Text("Connect & Sync", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    enabled: Boolean,
    onQrDetected: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }

    DisposableEffect(enabled) {
        if (enabled) consumed.set(false)
        onDispose {}
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewCtx ->
            val previewView = PreviewView(viewCtx)
            val cameraFuture = ProcessCameraProvider.getInstance(viewCtx)
            cameraFuture.addListener({
                runCatching {
                    val provider = cameraFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val reader = MultiFormatReader().apply {
                        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        decodeQrFromProxy(image, reader)?.let { text ->
                            if (!consumed.getAndSet(true)) {
                                ContextCompat.getMainExecutor(ctx).execute { onQrDetected(text) }
                            }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { Log.w("QrCameraPreview", "Camera bind failed", it) }
            }, ContextCompat.getMainExecutor(viewCtx))
            previewView
        },
    )
}

private fun decodeQrFromProxy(image: ImageProxy, reader: MultiFormatReader): String? {
    return try {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        reader.decodeWithState(bitmap).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
        image.close()
    }
}
