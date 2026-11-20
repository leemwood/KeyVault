package cn.lemwood.keyvault

import androidx.activity.result.ActivityResultRegistry
import androidx.lifecycle.LifecycleOwner
import cn.lemwood.keyvault.sync.PairingException
import cn.lemwood.keyvault.sync.PairingProblem
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CompletableDeferred

class PairingCaptureActivity : CaptureActivity()

internal class PairingQrScanner(registry: ActivityResultRegistry, owner: LifecycleOwner) {
    private var pending: CompletableDeferred<String?>? = null
    private val launcher = registry.register("keyvault_pairing_scan", owner, ScanContract()) { result ->
        val request = pending
        pending = null
        request?.complete(result.contents)
    }

    suspend fun scan(prompt: String): String? {
        if (pending != null) return null
        val request = CompletableDeferred<String?>()
        pending = request
        try {
            launcher.launch(ScanOptions().apply {
                setCaptureActivity(PairingCaptureActivity::class.java)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(prompt)
                setBeepEnabled(false)
            })
        } catch (_: RuntimeException) {
            pending = null
            throw PairingException(PairingProblem.INVALID_CODE)
        }
        return try { request.await() } finally { request.cancel() }
    }
}
