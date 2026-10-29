package dev.halftoast.stmobile.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.Security
import java.util.Base64

class KeyManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir: File get() = tmp.root

    private object FakeWrapper : KeyWrapper {
        override fun wrap(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
        override fun unwrap(bytes: ByteArray) = wrap(bytes)
    }

    companion object {
        /**
         * Mirrors StMobileApp: full BouncyCastle at position 1, as on the device. Left registered:
         * sshj caches "BC" as its provider for the rest of the JVM, like the app process.
         */
        @JvmStatic @BeforeClass fun registerBc() {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    @Test fun authorizedKeysLineFormat() {
        val line = KeyManager(dir, FakeWrapper).authorizedKeysLine()
        assertTrue(line.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(line.endsWith(" st-mobile"))
        assertEquals(3, line.split(" ").size)
    }

    @Test fun keyIsStableAcrossInstances() =
        assertEquals(KeyManager(dir, FakeWrapper).authorizedKeysLine(), KeyManager(dir, FakeWrapper).authorizedKeysLine())

    @Test fun storedBytesAreWrapped() {
        val kp = KeyManager(dir, FakeWrapper).getOrCreate()
        val raw = File(dir, "ssh_key.bin").readBytes()
        // no plain PKCS#8 header
        assertFalse(raw.toList().windowed(4).any { it == listOf<Byte>(0x30, 0x81.toByte(), 0x87.toByte(), 0x02) })
        // and not the private key encoding itself, whatever provider produced it
        val enc = kp.private.encoded.toList()
        assertFalse(raw.toList().windowed(enc.size).any { it == enc })
    }

    @Test fun publicKeyInLineRoundTripsThroughSshj() {
        val km = KeyManager(dir, FakeWrapper)
        val blob = Base64.getDecoder().decode(km.authorizedKeysLine().split(" ")[1])
        val parsed = Buffer.PlainBuffer(blob).readPublicKey()
        assertArrayEquals(km.getOrCreate().public.encoded, parsed.encoded)
    }

    @Test fun sshjSignsWithKeyUnderBouncyCastle() {
        assertEquals("BC", SecurityUtils.getSecurityProvider())
        val kp = KeyManager(dir, FakeWrapper).getOrCreate()
        assertEquals(KeyType.ECDSA256, KeyPairWrapper(kp).type)
        val data = "session-id".toByteArray()
        val signer = SignatureECDSA.Factory256().create().apply { initSign(kp.private); update(data) }
        // As sent in publickey auth: string algorithm, string signature
        val wire = Buffer.PlainBuffer().putString("ecdsa-sha2-nistp256").putBytes(signer.encode(signer.sign())).compactData
        val verifier = SignatureECDSA.Factory256().create().apply { initVerify(kp.public); update(data) }
        assertTrue(verifier.verify(wire))
    }
}
