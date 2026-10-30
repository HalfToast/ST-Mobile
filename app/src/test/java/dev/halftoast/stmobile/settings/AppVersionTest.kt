package dev.halftoast.stmobile.settings

import dev.halftoast.stmobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test fun namesTheVersionAndBuild() = assertEquals("ST Mobile 0.2.0 (build 2)", appVersionLabel("0.2.0", 2))

    @Test fun theBuildsOwnVersionIsShown() =
        assertEquals("ST Mobile ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", appVersionLabel())
}
