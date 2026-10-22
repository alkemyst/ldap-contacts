package org.ldapcontacts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Reads one QR code and hands its text back to [ConfigActivity].
 *
 * Deliberately built on the scanner library's plain [BarcodeView] instead of its
 * ready-made CaptureActivity: that one goes through CaptureManager, which calls
 * AndroidX compatibility classes its own POM does not declare, and it owns the whole
 * screen including the error handling. Here every failure, including one reported
 * asynchronously by the camera thread, is caught and sent back as a message, so a
 * phone that cannot scan says so instead of taking the app down with it.
 */
class ScanActivity : Activity() {
    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ERROR = "error"
        private const val RC_CAMERA = 1
        private const val TAG = "LdapContacts"
    }

    private lateinit var scanner: BarcodeView
    private lateinit var message: TextView
    private var decoding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        message = TextView(this).apply {
            text = "Point the camera at the configuration QR code"
            setPadding(pad, pad, pad, pad)
        }
        scanner = BarcodeView(this).apply {
            setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)))
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() {}
                override fun previewStarted() {}
                override fun previewStopped() {}
                override fun cameraClosed() {}
                override fun cameraError(error: Exception?) {
                    // The camera thread reports here; without this the exception would
                    // reach the default handler and kill the process.
                    fail("Camera error: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}")
                }
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(message, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), RC_CAMERA)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, granted: IntArray) {
        super.onRequestPermissionsResult(code, perms, granted)
        if (code != RC_CAMERA) return
        if (granted.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else fail("Camera permission refused: type the settings instead.")
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
    }

    override fun onPause() {
        super.onPause()
        runCatching { scanner.pause() }.onFailure { Log.w(TAG, "pausing the scanner failed", it) }
    }

    private fun start() {
        try {
            if (!decoding) {
                scanner.decodeSingle(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        val text = result?.text ?: return
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    }

                    override fun possibleResultPoints(resultPoints: List<ResultPoint>?) {}
                })
                decoding = true
            }
            scanner.resume()
        } catch (e: Throwable) {
            // Includes a NoClassDefFoundError from a dependency the library needs but
            // does not declare: worth a message rather than a crash.
            Log.w(TAG, "the scanner could not start", e)
            fail("Scanner unavailable: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** Back to the configuration screen, which shows [reason]. */
    private fun fail(reason: String) {
        if (isFinishing) return
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, reason))
        finish()
    }
}
