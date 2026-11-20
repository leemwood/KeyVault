package cn.lemwood.keyvault.sync

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12SafeBagFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncTlsIdentityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val loopback = InetSocketAddress("127.0.0.1", 0)

    @Test
    fun `desktop reopens the encrypted identity and keeps it after a wrong password`() {
        val file = temporaryFolder.root.toPath().resolve("device.p12")
        val identity = identity("device")
        val contents = file.toFile().readBytes()
        val visibleBagTypes = PKCS12PfxPdu(contents).contentInfos
            .filter { it.contentType == PKCSObjectIdentifiers.data }
            .flatMap { info -> PKCS12SafeBagFactory(info).safeBags.map { it.type } }
        assertTrue(PKCSObjectIdentifiers.pkcs8ShroudedKeyBag in visibleBagTypes)
        assertTrue(PKCSObjectIdentifiers.keyBag !in visibleBagTypes)
        assertFailsWith<IOException> {
            loadOrCreateDesktopSyncIdentity("incorrect".toCharArray(), file)
        }
        assertContentEquals(contents, file.toFile().readBytes())
        assertEquals(identity.certificatePin, identity("device").certificatePin)
    }

    @Test
    fun `pinned peers establish mutual TLS and exchange data`() {
        val server = identity("server")
        val client = identity("client")
        server.listen(loopback, client.certificatePin).use { listener ->
            val exchange = exchange(listener)
            client.connect(InetSocketAddress(loopback.address, listener.localPort), server.certificatePin)
                .use { socket ->
                    socket.outputStream.write(42)
                    assertEquals(43, socket.inputStream.read())
                    assertTrue(socket.session.protocol in setOf("TLSv1.2", "TLSv1.3"))
                }
            assertTrue(exchange.get(15, SECONDS) in setOf("TLSv1.2", "TLSv1.3"))
        }
    }

    @Test
    fun `client rejects a server with an unexpected certificate`() {
        val server = identity("server")
        val client = identity("client")
        server.listen(loopback, client.certificatePin).use { listener ->
            val rejected = background { assertFailsWith<IOException> { listener.accept().close() } }
            assertFailsWith<SSLException> {
                client.connect(InetSocketAddress(loopback.address, listener.localPort), client.certificatePin)
                    .close()
            }
            rejected.get(15, SECONDS)
        }
    }

    @Test
    fun `server rejects a client with an unexpected certificate`() {
        val server = identity("server")
        val client = identity("client")
        server.listen(loopback, server.certificatePin).use { listener ->
            val rejected = background { assertFailsWith<SSLException> { listener.accept().close() } }
            val address = InetSocketAddress(loopback.address, listener.localPort)
            val reply = runCatching {
                client.connect(address, server.certificatePin).use { it.inputStream.read() }
            }
            assertTrue(reply.getOrNull() == -1 || reply.exceptionOrNull() is IOException)
            rejected.get(15, SECONDS)
        }
    }

    @Test
    fun `Android Keystore identity completes a TLS handshake with desktop`() {
        val adb = System.getenv("KEYVAULT_TEST_ADB")
        assumeTrue("Set KEYVAULT_TEST_ADB to run the Android and desktop smoke test", !adb.isNullOrBlank())
        val executable = requireNotNull(adb)
        val runner = "cn.lemwood.keyvault.debug.test/cn.lemwood.keyvault.sync.SyncTlsSmokeInstrumentation"
        val androidResult = adbOutput(executable, "shell", "am", "instrument", "-w", runner)
        assertTrue("INSTRUMENTATION_RESULT: syncTls=ok" in androidResult, androidResult)
        val androidPin = requireNotNull(Regex("certificatePin=([A-Za-z0-9_-]{43})")
            .find(androidResult)).groupValues[1]
        val desktop = identity("desktop")
        desktop.listen(loopback, androidPin).use { listener ->
            val port = adbOutput(executable, "reverse", "tcp:0", "tcp:${listener.localPort}").trim().toInt()
            try {
                val exchange = exchange(listener)
                val result = adbOutput(
                    executable, "shell", "am", "instrument", "-w",
                    "-e", "port", port.toString(), "-e", "peerPin", desktop.certificatePin, runner,
                )
                val desktopResult = runCatching { exchange.get(15, SECONDS) }
                assertTrue("INSTRUMENTATION_RESULT: syncTls=ok" in result, "$result\n$desktopResult")
                assertTrue("tlsVersion=${desktopResult.getOrThrow()}" in result, result)
            } finally {
                adbOutput(executable, "reverse", "--remove", "tcp:$port")
            }
        }
    }

    private fun identity(name: String): SyncTlsIdentity {
        val password = "sync test passphrase".toCharArray()
        try {
            return loadOrCreateDesktopSyncIdentity(password, temporaryFolder.root.toPath().resolve("$name.p12"))
        } finally {
            password.fill('\u0000')
        }
    }

    private fun exchange(listener: SyncTlsListener): FutureTask<String> = background {
        listener.accept().use { socket ->
            assertEquals(42, socket.inputStream.read())
            socket.outputStream.write(43)
            socket.session.protocol
        }
    }

    private fun <T> background(action: () -> T): FutureTask<T> = FutureTask(action).also { task ->
        thread(isDaemon = true) { task.run() }
    }

    private fun adbOutput(adb: String, vararg args: String): String {
        val process = ProcessBuilder(adb, *args).redirectErrorStream(true).start()
        try {
            check(process.waitFor(30, SECONDS)) { "Timed out waiting for adb" }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
