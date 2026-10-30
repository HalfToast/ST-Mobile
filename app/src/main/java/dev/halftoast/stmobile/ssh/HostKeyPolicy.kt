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

/** What the server presented compared with the pin, for showing to the user before trusting. */
sealed interface HostKeyCheck {
    data class Matches(val fingerprint: String) : HostKeyCheck
    data class Unpinned(val presented: String) : HostKeyCheck
    data class Changed(val expected: String, val actual: String) : HostKeyCheck
}

/**
 * Trust on first use. A mismatch is never auto-trusted: the user must confirm the new fingerprint with
 * "Trust new host key" in Settings, which pins exactly the key they were shown ([pinAfterConfirm]).
 */
object HostKeyPolicy {
    fun decide(known: String?, presented: String): HostKeyDecision = when {
        known.isNullOrEmpty() -> HostKeyDecision.TrustAndStore
        known == presented -> HostKeyDecision.Accept
        else -> HostKeyDecision.Reject
    }

    fun check(known: String?, presented: String): HostKeyCheck = when (decide(known, presented)) {
        HostKeyDecision.TrustAndStore -> HostKeyCheck.Unpinned(presented)
        HostKeyDecision.Accept -> HostKeyCheck.Matches(presented)
        HostKeyDecision.Reject -> HostKeyCheck.Changed(known.orEmpty(), presented)
    }

    /**
     * The pin to store after the user confirmed [check]: exactly the fingerprint shown to them.
     * Never null, so re-trusting can't fall back to silently pinning whatever the next connection sees.
     */
    fun pinAfterConfirm(check: HostKeyCheck): String = when (check) {
        is HostKeyCheck.Matches -> check.fingerprint
        is HostKeyCheck.Unpinned -> check.presented
        is HostKeyCheck.Changed -> check.actual
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
