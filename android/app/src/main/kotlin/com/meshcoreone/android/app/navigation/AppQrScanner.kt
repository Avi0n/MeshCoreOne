// AndroidOnly: WP-407 CameraX channel scanner adapter; denial/failure leaves a usable manual channel-link input.
package com.meshcoreone.android.app.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.meshcoreone.android.app.platform.packLuma
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppQrScanner(
    onDismiss: () -> Unit,
    onDecoded: (String) -> Unit,
    camera: @Composable (onDecoded: (String) -> Unit, onFailure: () -> Unit) -> Unit = { decoded, failed ->
        QrCameraPreview(decoded, failed)
    },
) {
    val context = LocalContext.current
    val decode by rememberUpdatedState(onDecoded)
    val delivered = remember { AtomicBoolean() }
    val decodeOnce: (String) -> Unit = { if (delivered.compareAndSet(false, true)) decode(it) }
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraFailure by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {}
    }
    var input by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) cameraFailure = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (granted && !cameraFailure) {
                camera(decodeOnce) { cameraFailure = true }
            } else {
                Text(stringResource(C.contactsScanUnavailableDescription))
                Button(onClick = {
                    granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) cameraFailure = false else permission.launch(Manifest.permission.CAMERA)
                }) {
                    Text(stringResource(C.contactsAddScanQR))
                }
            }
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(C.contactsAddPasteURL)) })
            Button(onClick = { decodeOnce(input) }, enabled = input.isNotBlank()) {
                Text(stringResource(C.contactsAddView))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(L.commonCancel)) }
        }
    }
}

@Composable
private fun QrCameraPreview(onDecoded: (String) -> Unit, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val decode by rememberUpdatedState(onDecoded)
    val cameraFailed by rememberUpdatedState(onFailure)
    val view = remember(context) { PreviewView(context) }
    val main = remember(context) { context.mainExecutor }
    DisposableEffect(lifecycle, view) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var provider: ProcessCameraProvider? = null
        val disposed = AtomicBoolean()
        val delivered = AtomicBoolean()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (disposed.get() || delivered.get()) return@setAnalyzer
                val plane = image.planes.first()
                val crop = image.cropRect
                val packed = packLuma(plane.buffer, plane.rowStride, plane.pixelStride,
                    crop.left, crop.top, crop.width(), crop.height(), image.imageInfo.rotationDegrees)
                val source = PlanarYUVLuminanceSource(
                    packed.bytes, packed.width, packed.height, 0, 0, packed.width, packed.height, false,
                )
                val result = MultiFormatReader().decode(
                    BinaryBitmap(HybridBinarizer(source)),
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
                )
                if (delivered.compareAndSet(false, true)) main.execute {
                    if (!disposed.get()) decode(result.text)
                }
            } catch (_: ReaderException) {
                // A frame with no decodable QR is normal, not a failed camera.
            } catch (failure: RuntimeException) {
                Logger.getLogger("com.mc1.ChannelScanner").log(Level.WARNING, "Camera luma analysis failed", failure)
                main.execute { if (!disposed.get()) cameraFailed() }
            } finally {
                image.close()
            }
        }
        fun failed(cause: Exception) {
            Logger.getLogger("com.mc1.ChannelScanner").log(Level.WARNING, "Camera QR unavailable", cause)
            cameraFailed()
        }
        future.addListener({
            if (!disposed.get()) {
                try {
                    val current = future.get()
                    provider = current
                    current.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (failure: ExecutionException) {
                    failed(failure)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failed(failure)
                } catch (failure: SecurityException) {
                    failed(failure)
                } catch (failure: IllegalArgumentException) {
                    failed(failure)
                } catch (failure: IllegalStateException) {
                    failed(failure)
                }
            }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(240.dp))
}
