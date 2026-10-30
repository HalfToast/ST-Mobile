package dev.halftoast.stmobile.stats

import dev.halftoast.stmobile.panel.Probes
import dev.halftoast.stmobile.panel.RemoteCommand
import dev.halftoast.stmobile.ssh.ExecResult
import dev.halftoast.stmobile.ssh.RemoteShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Answer(val result: ExecResult) : RemoteShell {
        override suspend fun exec(command: String, stdin: String?, timeoutMs: Long, signalOnCancel: Boolean) = result
        override fun stream(command: String, stdin: String?): Flow<String> = emptyFlow()
    }

    private fun errorFor(
        result: ExecResult,
        probe: () -> RemoteCommand = { RemoteCommand("env ST_USER='default-user' python3 -", "") },
    ): String? {
        var error: String? = null
        runTest(dispatcher) {
            val vm = StatsViewModel(Answer(result), probe)
            vm.refresh()
            advanceUntilIdle()
            error = vm.ui.value.error
        }
        return error
    }

    @Test fun saysWhenPython3IsMissing() {
        val expected = "Stats needs Python 3 on the server (on a Mac: install the Command Line Tools)"
        assertEquals(expected, errorFor(ExecResult(127, "", "env: 'python3': No such file or directory\n")))
        assertEquals(expected, errorFor(ExecResult(127, "", "env: python3: No such file or directory\n")))
        // macOS's /usr/bin/python3 without the Command Line Tools.
        val xcrun = "xcrun: error: invalid active developer path (/Library/Developer/CommandLineTools), " +
            "missing xcrun at: /Library/Developer/CommandLineTools/usr/bin/xcrun\n"
        assertEquals(expected, errorFor(ExecResult(1, "", xcrun)))
    }

    @Test fun otherFailuresShowTheirLastLine() {
        assertEquals(
            "Couldn't read the stats: PermissionError: denied",
            errorFor(ExecResult(1, "", "Traceback (most recent call last):\nPermissionError: denied\n")),
        )
        assertEquals("Couldn't read the stats: bash: no", errorFor(ExecResult(127, "", "bash: no\n")))
    }

    @Test fun aMissingSillyTavernFolderNamesTheSetting() {
        assertEquals(
            "SillyTavern wasn't found in /srv/st — check the SillyTavern folder in Settings",
            errorFor(ExecResult(2, "", "SillyTavern folder not found: /srv/st\n")),
        )
    }

    @Test fun badFolderSettingsAreNamed() {
        val ok = ExecResult(0, "", "")
        assertEquals(
            "The SillyTavern folder in Settings is invalid",
            errorFor(ok) { Probes.statsWith("x", "SillyTavern", "default-user") },
        )
        assertEquals(
            "The SillyTavern user folder in Settings is invalid",
            errorFor(ok) { Probes.statsWith("x", "", "a'b") },
        )
    }
}
