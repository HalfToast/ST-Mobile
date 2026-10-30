package dev.halftoast.stmobile.stats

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

    private fun errorFor(result: ExecResult): String? {
        var error: String? = null
        runTest(dispatcher) {
            val vm = StatsViewModel(Answer(result)) { RemoteCommand("env ST_USER='default-user' python3 -", "") }
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
    }

    @Test fun otherFailuresShowTheirLastLine() {
        assertEquals(
            "Couldn't read the stats: PermissionError: denied",
            errorFor(ExecResult(1, "", "Traceback (most recent call last):\nPermissionError: denied\n")),
        )
        assertEquals("Couldn't read the stats: bash: no", errorFor(ExecResult(127, "", "bash: no\n")))
    }
}
