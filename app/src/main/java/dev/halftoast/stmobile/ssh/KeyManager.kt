package dev.halftoast.stmobile.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import net.schmizz.sshj.common.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the SSH private key at rest. */
interface KeyWrapper {
    fun wrap(bytes: ByteArray): ByteArray
    fun unwrap(bytes: ByteArray): ByteArray
}

/**
 * The app's SSH identity: an ECDSA P-256 key generated in software (sshj can't sign with
 * Keystore-resident keys reliably). The encoded key pair is stored only in wrapped form; the
 * wrapping key is a non-exportable AES-256-GCM key in Android Keystore. Never log key material.
 */
class KeyManager(private val filesDir: File, private val wrapper: KeyWrapper) {
    private val file = File(filesDir, FILE_NAME)

    @Volatile private var cached: KeyPair? = null

    @Synchronized
    fun getOrCreate(): KeyPair {
        cached?.let { return it }
        val kp = load() ?: run {
            // Kept aside, never overwritten: the tablet still trusts this identity.
            if (file.exists()) file.renameTo(File(filesDir, "$FILE_NAME.bad"))
            create()
        }
        cached = kp
        return kp
    }

    /** `ecdsa-sha2-nistp256 <base64 blob> st-mobile`, the line KeyInstaller appends to authorized_keys. */
    fun authorizedKeysLine(): String {
        val blob = Buffer.PlainBuffer().putPublicKey(getOrCreate().public).compactData
        return "$KEY_TYPE ${Base64.getEncoder().encodeToString(blob)} $COMMENT"
    }

    /**
     * Null if there is no stored key or it can never be unwrapped (the Keystore key is gone or the
     * file is corrupt). Throws on anything else, e.g. a transient Keystore error: the call fails and
     * is retried later, rather than replacing the identity the tablet trusts.
     */
    private fun load(): KeyPair? {
        if (!file.exists()) return null
        val plain = try {
            wrapper.unwrap(file.readBytes())
        } catch (e: Exception) {
            if (isPermanent(e)) return null
            throw e
        }
        return try {
            decode(plain)
        } catch (_: Exception) {
            null
        } finally {
            plain.fill(0)
        }
    }

    private fun create(): KeyPair {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val plain = encode(kp)
        try {
            val wrapped = wrapper.wrap(plain)
            filesDir.mkdirs()
            val tmp = File(filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(wrapped)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("could not store SSH key")
            }
        } finally {
            plain.fill(0)
        }
        return kp
    }

    private fun encode(kp: KeyPair): ByteArray {
        val pub = kp.public.encoded
        val priv = kp.private.encoded
        try {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use {
                it.writeByte(FORMAT_VERSION)
                it.writeInt(pub.size); it.write(pub)
                it.writeInt(priv.size); it.write(priv)
            }
            return out.toByteArray()
        } finally {
            priv.fill(0)
        }
    }

    private fun decode(plain: ByteArray): KeyPair {
        val input = DataInputStream(ByteArrayInputStream(plain))
        require(input.readUnsignedByte() == FORMAT_VERSION) { "unknown key format" }
        val pub = ByteArray(input.readInt()).also { input.readFully(it) }
        val priv = ByteArray(input.readInt()).also { input.readFully(it) }
        try {
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub)), kf.generatePrivate(PKCS8EncodedKeySpec(priv)))
        } finally {
            priv.fill(0)
        }
    }

    private fun isPermanent(e: Exception) =
        e is AEADBadTagException || e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException ||
            e is IndexOutOfBoundsException || e is IllegalArgumentException // a truncated or garbled file

    companion object {
        const val FILE_NAME = "ssh_key.bin"
        const val KEY_TYPE = "ecdsa-sha2-nistp256"
        const val COMMENT = "st-mobile"
        private const val FORMAT_VERSION = 1
    }
}

/** AES-256-GCM with a non-exportable key in AndroidKeyStore. Output: [ivLen][iv][ciphertext+tag]. */
class AndroidKeystoreWrapper(private val alias: String = ALIAS) : KeyWrapper {
    override fun wrap(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(bytes)
    }

    override fun unwrap(bytes: ByteArray): ByteArray {
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val ALIAS = "stmobile_ssh_wrap"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
