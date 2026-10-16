package one.meshcore.android

import androidx.activity.compose.LocalActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android camera slot backing the contact and channel QR scanners (both share the
 * `(onDecoded, onPermissionDenied)` signature). It gates on the CAMERA runtime permission through the shared
 * [PermissionController] — requesting it on first show and routing a denial to [onPermissionDenied] (which the
 * scan controllers turn into the permission-prompt state) — then binds a CameraX preview with an ML Kit
 * QR analyzer that feeds each decoded string to [onDecoded]. The scan controllers own the parse/import and
 * their own in-flight guard, so a repeated decode of the same code is harmless.
 */
@Composable
fun QrCameraScanner(
    permissions: PermissionController,
    onDecoded: (String) -> Unit,
    onPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionState by permissions.state.collectAsState()
    val granted = OnboardingPermission.CAMERA in permissionState.granted
    val determined = OnboardingPermission.CAMERA in permissionState.determined
    val latestOnPermissionDenied by rememberUpdatedState(onPermissionDenied)

    LaunchedEffect(permissions) {
        permissions.refresh()
        if (OnboardingPermission.CAMERA !in permissions.state.value.granted) {
            permissions.request(OnboardingPermission.CAMERA)
        }
    }
    LaunchedEffect(determined, granted) {
        if (determined && !granted) latestOnPermissionDenied()
    }

    if (granted) CameraPreview(onDecoded = onDecoded, modifier = modifier)
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalActivity.current as? LifecycleOwner ?: return
    val latestOnDecoded by rememberUpdatedState(onDecoded)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context.applicationContext)
    }
    val active = remember { AtomicBoolean(true) }
    val analyzer = remember { QrCodeAnalyzer { if (active.get()) latestOnDecoded(it) } }

    DisposableEffect(cameraProviderFuture, analysisExecutor, analyzer) {
        onDispose {
            active.set(false)
            if (cameraProviderFuture.isDone) runCatching { cameraProviderFuture.get().unbindAll() }
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                if (!active.get()) return@addListener
                val preview = Preview.Builder().build()
                    .apply { surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        },
    )
}

/** An [ImageAnalysis.Analyzer] that decodes QR codes from the camera stream with ML Kit barcode scanning. */
private class QrCodeAnalyzer(private val onDecoded: (String) -> Unit) :
    ImageAnalysis.Analyzer,
    AutoCloseable {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onDecoded)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    override fun close() {
        scanner.close()
    }
}
