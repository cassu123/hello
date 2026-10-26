package org.hello.reader.feature.onboarding.qr

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer that decodes QR codes with ML Kit and reports the first
 * valid quick-connect payload exactly once. Invalid codes are reported through
 * [onInvalidCode] (at most once per distinct value) so the UI can explain why
 * nothing happened.
 */
class QrCodeAnalyzer(
    private val onPayload: (QrConnectPayload) -> Unit,
    private val onInvalidCode: () -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private val delivered = AtomicBoolean(false)

    @Volatile
    private var lastInvalid: String? = null

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || delivered.get()) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes -> handle(barcodes.mapNotNull { it.rawValue }) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handle(values: List<String>) {
        for (value in values) {
            val payload = QrConnectPayload.parse(value)
            if (payload != null) {
                if (delivered.compareAndSet(false, true)) onPayload(payload)
                return
            }
            if (value != lastInvalid) {
                lastInvalid = value
                onInvalidCode()
            }
        }
    }

    /** Allows another scan after a failed connection attempt. */
    fun reset() {
        delivered.set(false)
        lastInvalid = null
    }

    override fun close() {
        scanner.close()
    }
}
