package dev.halftoast.stmobile.ssh

import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

sealed interface HostKeyDecision {
    data object TrustAndStore : HostKeyDecision
    data object Accept : HostKeyDecision
    data object Reject : HostKeyDecision
}

/** Trust on first use. A mismatch is never auto-trusted; the user must clear the pin in Settings. */
object HostKeyPolicy {
    fun decide(known: String?, presented: String): HostKeyDecision = when {
        known.isNullOrEmpty() -> HostKeyDecision.TrustAndStore
        known == presented -> HostKeyDecision.Accept
        else -> HostKeyDecision.Reject
    }

    /**
     * OpenSSH-style `SHA256:<base64, unpadded>` of the key blob, as `ssh-keygen -lf` prints it.
     * (sshj's SecurityUtils.getFingerprint is MD5 hex, so it isn't used here.)
     */
    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
