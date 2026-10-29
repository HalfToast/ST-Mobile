package dev.halftoast.stmobile.ssh

import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class HostKeyPolicyTest {
    @Test fun firstSightTrusts() = assertEquals(HostKeyDecision.TrustAndStore, HostKeyPolicy.decide(null, "SHA256:abc"))
    @Test fun matchAccepts() = assertEquals(HostKeyDecision.Accept, HostKeyPolicy.decide("SHA256:abc", "SHA256:abc"))
    @Test fun mismatchRejects() = assertEquals(HostKeyDecision.Reject, HostKeyPolicy.decide("SHA256:abc", "SHA256:xyz"))

    /** Same format and value as `ssh-keygen -lf` (vector generated with OpenSSH). */
    @Test fun fingerprintMatchesOpenSsh() {
        val blob = Base64.getDecoder().decode(
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEv3RkJ0ZxMeOU9BKiQm1uU4alb9P46XirTbWcQHsvfxzsKWgxFi/lU1JBD1OFK8g9JHP/Fvx0gKAYUjeP6C2Wg=",
        )
        val key = Buffer.PlainBuffer(blob).readPublicKey()
        assertEquals("SHA256:DRjGpYdE9JsgDEpF3XlWlK/fZ4wGYcLMfAFmB28Cz9k", HostKeyPolicy.fingerprint(key))
    }
}
