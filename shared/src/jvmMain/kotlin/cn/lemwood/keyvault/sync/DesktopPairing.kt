package cn.lemwood.keyvault.sync

import cn.lemwood.keyvault.platform.createVaultDataStore
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.imageio.ImageIO
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class DesktopLanPairing : LanPairing {
    override val requiresPassword = true
    override val scanLabel = "读取二维码图片"

    override fun copyInvitation(code: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
    }

    override suspend fun unlock(password: CharArray): PairingDevice = withContext(Dispatchers.IO) {
        storageOperation {
            val (tls, signer) = loadOrCreateDesktopPairingIdentity(password)
            val name = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "KeyVault Desktop"
            JvmPairingDevice(tls, signer, name, createVaultDataStore(), DesktopDiscovery)
        }
    }

    override suspend fun scan(prompt: String): String? {
        val picker = FileDialog(null as Frame?, "选择二维码图片", FileDialog.LOAD)
        val file = try {
            picker.isVisible = true
            picker.file?.let { File(picker.directory, it) }
        } finally {
            picker.dispose()
        } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                decodeQrImage(file)
            } catch (_: Exception) {
                throw PairingException(PairingProblem.INVALID_CODE)
            }
        }
    }
}

internal fun decodeQrImage(file: File): String {
    require(file.length() in 1..(16L * 1024 * 1024))
    ImageIO.createImageInputStream(file).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().first()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width in 1..4096 && height in 1..4096)
            val image = reader.read(0)
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
            return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
        } finally {
            reader.dispose()
        }
    }
}

internal object DesktopDiscovery : LanDiscovery {
    private val serviceType = "${MDNS_SERVICE}local."

    override fun addresses(): List<LanAddress> = NetworkInterface.getNetworkInterfaces().toList().flatMap { network ->
        if (!network.isUp || network.isLoopback || network.isPointToPoint) return@flatMap emptyList()
        network.interfaceAddresses.mapNotNull { entry ->
            val ip = entry.address as? Inet4Address ?: return@mapNotNull null
            LanAddress(ip, entry.networkPrefixLength.toInt())
        }
    }.sortedByDescending { it.ip.isSiteLocalAddress }

    override fun discover() = callbackFlow {
        val devices = mutableMapOf<Inet4Address, MutableSet<String>>()
        val instances = mutableListOf<JmDNS>()
        try {
            for (link in addresses()) {
                val dns = runCatching { JmDNS.create(link.ip) }.getOrNull() ?: continue
                instances += dns
                dns.addServiceListener(serviceType, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) = publish(event, add = true)
                    override fun serviceResolved(event: ServiceEvent) = Unit
                    override fun serviceRemoved(event: ServiceEvent) = publish(event, add = false)
                    private fun publish(event: ServiceEvent, add: Boolean) {
                        synchronized(devices) {
                            val names = devices.getOrPut(link.ip) { linkedSetOf() }
                            if (add) names.add(event.name) else names.remove(event.name)
                            trySend(devices.values.flatten().distinct().sorted())
                        }
                    }
                })
            }
            awaitClose()
        } finally {
            instances.forEach { runCatching { it.close() } }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun announce(address: Inet4Address, port: Int, name: String) = withContext(Dispatchers.IO) {
        JmDNS.create(address).use { dns ->
            dns.registerService(ServiceInfo.create(serviceType, name, port, 0, 0, "pairing=1"))
            awaitCancellation()
        }
    }
}
