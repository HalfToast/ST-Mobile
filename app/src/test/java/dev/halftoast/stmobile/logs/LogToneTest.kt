package dev.halftoast.stmobile.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class LogToneTest {
    @Test fun tonesByKeyword() {
        assertEquals(LogTone.Error, logTone("Error: connect ECONNREFUSED 127.0.0.1:5001"))
        assertEquals(LogTone.Error, logTone("  at Object.<anonymous> [ERROR] boom"))
        assertEquals(LogTone.Warn, logTone("Warning: config.yaml has unknown key"))
        assertEquals(LogTone.Warn, logTone("[WARN] slow request"))
        assertEquals(LogTone.Normal, logTone("'z-ai/glm-latest'"))
        assertEquals(LogTone.Normal, logTone("Terrorists are fictional")) // whole-word match only
    }
}
