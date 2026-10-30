package dev.halftoast.stmobile.logs

import dev.halftoast.stmobile.panel.LogKind
import dev.halftoast.stmobile.panel.Probes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogNoticeTest {
    @Test fun noLogFileYet() {
        assertEquals(
            "No log file at /srv/st/sillytavern.log yet. It appears once SillyTavern is started by stpanel or this app.",
            logNotice("${Probes.TAIL_NO_FILE}/srv/st/sillytavern.log", LogKind.Server),
        )
        assertEquals(
            "No access log at /srv/st/data/access.log yet. SillyTavern writes it when a browser connects.",
            logNotice("${Probes.TAIL_NO_FILE}/srv/st/data/access.log", LogKind.Access),
        )
    }

    @Test fun noSillyTavernFolder() = assertEquals(
        "SillyTavern wasn't found in /srv/st — check the SillyTavern folder in Settings",
        logNotice("${Probes.TAIL_NO_ST_DIR}/srv/st", LogKind.Server),
    )

    @Test fun ordinaryLinesAreNoNotice() = assertNull(logNotice("SillyTavern is listening on port 8000", LogKind.Server))
}
