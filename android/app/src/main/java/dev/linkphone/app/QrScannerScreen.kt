package dev.linkphone.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Size
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What a `linkphone://pair?...` QR code carries. */
data class PairPayload(
    val code: String,
    val port: Int,
    val addresses: List<String>,
    val ipv6Addresses: List<String>,
    val serverName: String,
    val serverId: String
)

private const val TAG = "LinkPhoneQr"

/**
 * Parses the payload rendered by the desktop's PairingCard.
 *
 * `linkphone://pair?c=<code>&p=<port>&a=<addr,addr>&n=<name>&i=<serverId>&v=1`
 */
fun parsePairPayload(raw: String): PairPayload? {
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals("linkphone", ignoreCase = true)) return null
    if (!uri.host.equals("pair", ignoreCase = true)) return null

    val code = uri.getQueryParameter("c")?.trim()?.uppercase().orEmpty()
    if (code.length < 6) return null

    val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8765
    val addresses = uri.getQueryParameter("a")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    // Link-local IPv6, used when the router isolates IPv4 clients.
    val ipv6Addresses = uri.getQueryParameter("a6")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.contains(':') }
        .orEmpty()

    return PairPayload(
        code = code,
        port = port,
        addresses = addresses,
        ipv6Addresses = ipv6Addresses,
        serverName = uri.getQueryParameter("n").orEmpty().ifBlank { "PC" },
        serverId = uri.getQueryParameter("i").orEmpty()
    )
}

/** Prefer a private-range IPv4 address: a PC with a VPN or Hyper-V adapter reports several. */
fun preferredAddress(addresses: List<String>): String? =
    addresses.firstOrNull { it.startsWith("192.168.") }
        ?: addresses.firstOrNull { it.startsWith("10.") }
        ?: addresses.firstOrNull {
            it.startsWith("172.") &&
                it.split('.').getOrNull(1)?.toIntOrNull()?.let { second -> second in 16..31 } == true
        }
        ?: addresses.firstOrNull()

@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val handled = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (!ok) error = "Camera permission is required to scan the QR code."
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (granted) {
                    val previewView = remember {
                        PreviewView(context).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    }

                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                    DisposableEffect(previewView, lifecycleOwner) {
                        val future = ProcessCameraProvider.getInstance(context)
                        var provider: ProcessCameraProvider? = null
                        var active = true

                        future.addListener({
                            if (!active) return@addListener
                            try {
                                val bound = future.get()
                                provider = bound

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                @Suppress("DEPRECATION")
                                val analysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setOutputImageFormat(
                                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                    )
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .build()

                                analysis.setAnalyzer(executor) { image ->
                                    if (handled.get()) {
                                        image.close()
                                        return@setAnalyzer
                                    }
                                    val text = decodeQr(image)
                                    if (text != null && handled.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            onResult(text)
                                        }
                                    }
                                }

                                bound.unbindAll()
                                bound.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "cannot start camera", e)
                                error = "Camera unavailable: ${e.message ?: "unknown error"}"
                            }
                        }, ContextCompat.getMainExecutor(context))

                        onDispose {
                            active = false
                            runCatching { provider?.unbindAll() }
                        }
                    }

                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(260.dp)
                            .border(2.dp, Color.White, RoundedCornerShape(20.dp))
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 32.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Scan the QR on your PC",
                        color = Color.White,
                        fontSize = 17.sp
                    )
                    Text(
                        "LinkPhone → sidebar → “Scan QR code instead”",
                        color = Color(0xFFB3B3B3),
                        fontSize = 12.sp
                    )
                }

                error?.let { message ->
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                            .background(Color(0xE6121212), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                    }
                }

                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                ) {
                    Text("Cancel", color = Color.White)
                }
            }
        }
    }
}

/**
 * RGBA frame -> Bitmap -> ZXing. Always closes the [ImageProxy]; CameraX will
 * stop delivering frames otherwise.
 */
private fun decodeQr(image: ImageProxy): String? {
    val created = ArrayList<Bitmap>(3)
    try {
        if (image.width <= 0 || image.height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null

        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        if (paddedWidth < width) return null

        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        created.add(raw)
        raw.copyPixelsFromBuffer(buffer)

        val cropped = if (paddedWidth != width) {
            Bitmap.createBitmap(raw, 0, 0, width, height).also { created.add(it) }
        } else {
            raw
        }

        val rotation = image.imageInfo.rotationDegrees
        val upright = if (rotation == 0) {
            cropped
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
                .also { created.add(it) }
        }

        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)

        val source = RGBLuminanceSource(upright.width, upright.height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        return MultiFormatReader().apply { setHints(hints) }.decodeWithState(binary).text
    } catch (_: NotFoundException) {
        return null
    } catch (e: Exception) {
        Log.d(TAG, "frame skipped: ${e.message}")
        return null
    } finally {
        for (bitmap in created) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        image.close()
    }
}
