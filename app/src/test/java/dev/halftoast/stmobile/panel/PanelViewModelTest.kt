package dev.halftoast.stmobile.panel

import dev.halftoast.stmobile.ssh.ExecResult
import dev.halftoast.stmobile.ssh.RemoteShell
import dev.halftoast.stmobile.ssh.SshError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DASHBOARD_CMD = "ST_USER='default-user' bash -s"
private const val WHITELIST_CMD = "bash -s # whitelist"

private const val ONLINE = "online=1\npid=9824\netime=01:34:17\nhttp_code=200\nlatency_s=0.004\nbranch=staging\ncommit=ad29cbda6\nbehind=0\n" +
    "last_client=100.101.102.104 (Mozilla/5.0 (Linux; Android 16; K) at 02:59:29 UTC\n"
/** In [FakeRemoteShell.dashboards] / [FakeRemoteShell.whitelists]: that call throws Unreachable. */
private const val FAIL = "FAIL"
private const val WL_OFF = "mode=false\nentry=127.0.0.1\n"
private const val WL_ON = "mode=true\nentry=127.0.0.1\n"
/** Process alive but HTTP didn't answer (curl gave 000). */
private const val NO_HTTP = "online=1\npid=9824\nhttp_code=000\nlatency_s=0\nbranch=staging\ncommit=ad29cbda6\nbehind=0\n"
private const val OFFLINE = "online=0\nbranch=staging\ncommit=ad29cbda6\nbehind=0\n"

/** Records every exec; answers dashboard/whitelist probes and stpanel subcommands with canned output. */
class FakeRemoteShell : RemoteShell {
    val commands = mutableListOf<String>()
    private val calls = mutableListOf<Triple<String, String?, Long>>()
    var delayMs = 0L
    /** Extra delay for `stpanel` commands only. */
    var actionDelayMs = 0L
    var dashboardCalls = 0
        private set
    /** Dashboard outputs to return in order; the last one repeats. */
    var dashboards = mutableListOf(ONLINE)
    var whitelists = mutableListOf(WL_OFF)
    var updateOutput = "Updating ad29cbda6..1234567890ab\nFast-forward\n"
    private var failure: Exception? = null

    fun failWith(e: Exception?) { failure = e }
    fun stdinFor(prefix: String): String? = calls.firstOrNull { it.first.contains(prefix) }?.second
    fun timeoutFor(prefix: String): Long? = calls.firstOrNull { it.first.contains(prefix) }?.third

    override suspend fun exec(command: String, stdin: String?, timeoutMs: Long): ExecResult {
        commands += command
        calls += Triple(command, stdin, timeoutMs)
        if (command == DASHBOARD_CMD) dashboardCalls++
        if (delayMs > 0) delay(delayMs)
        if (actionDelayMs > 0 && command.startsWith("stpanel")) delay(actionDelayMs)
        failure?.let { throw it }
        val out = when {
            command == DASHBOARD_CMD -> next(dashboards)
            command == WHITELIST_CMD -> next(whitelists)
            command == "stpanel update" -> updateOutput
            command == "stpanel backup" -> "✓ Backup successfully created!\n  Archive Size: 12M\n"
            command == "stpanel clean" -> "✓ Cache successfully cleared!\n"
            command == "stpanel whitelist" && stdin?.startsWith("2\n") == true ->
                "✓ Added ${stdin.lines()[1]} to whitelist!\n(Note: Restart SillyTavern for config changes to take effect)\n"
            command == "stpanel whitelist" && stdin == "1\n" ->
                "✓ Whitelist Mode toggled to: ENABLED\n(Note: Restart SillyTavern for config changes to take effect)\n"
            else -> ""
        }
        return ExecResult(0, out, "")
    }

    private fun next(outputs: MutableList<String>): String {
        val out = if (outputs.size > 1) outputs.removeAt(0) else outputs[0]
        if (out == FAIL) throw SshError.Unreachable("no route")
        return out
    }

    override fun stream(command: String): Flow<String> = emptyFlow()
}

class FakeProbes : ProbeSource {
    var invalidUserDir = false
    override fun dashboard(): Pair<String, String> {
        require(!invalidUserDir) { "Invalid SillyTavern user folder" }
        return DASHBOARD_CMD to "# dashboard.sh"
    }
    override fun whitelist(): Pair<String, String> = WHITELIST_CMD to "# whitelist.sh"
}

@OptIn(ExperimentalCoroutinesApi::class)
class PanelViewModelTest {
    private lateinit var shell: FakeRemoteShell
    private lateinit var probes: FakeProbes
    private lateinit var vm: PanelViewModel
    private var updatedCalls = 0
    private var restartedCalls = 0
    private var dashboardCallsAtRestart = -1
    private val downReasons = mutableListOf<String>()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shell = FakeRemoteShell()
        probes = FakeProbes()
        vm = PanelViewModel(shell, probes, onUpdated = { updatedCalls++ }, onServerRestarted = {
            restartedCalls++
            dashboardCallsAtRestart = shell.dashboardCalls
        }, onServerDown = { downReasons += it })
    }

    @After fun tearDown() = Dispatchers.resetMain()

    // --- From the brief, verbatim ---

    @Test fun stopNeedsConfirm() = runTest { vm.request(PanelAction.Stop); assertEquals(PanelAction.Stop, vm.ui.value.pendingConfirm); assertTrue(shell.commands.none { it.contains("stpanel stop") }) }
    @Test fun startRunsWithoutConfirm() = runTest { vm.request(PanelAction.Start); advanceUntilIdle(); assertTrue(shell.commands.any { it.contains("stpanel start") }) }
    @Test fun cleanFeedsYes() = runTest { vm.request(PanelAction.CleanCache); vm.confirm(); advanceUntilIdle(); assertEquals("y\n", shell.stdinFor("stpanel clean")) }
    @Test fun whitelistAddFeedsChoiceAndIp() = runTest { vm.addWhitelistEntry("192.168.1.50"); advanceUntilIdle(); assertEquals("2\n192.168.1.50\n", shell.stdinFor("stpanel whitelist")); assertTrue(vm.ui.value.restartSuggested) }
    @Test fun whitelistRejectsBadIpWithoutRunning() = runTest { vm.addWhitelistEntry("1.2.3.4\"); x"); advanceUntilIdle(); assertTrue(shell.commands.none { it.contains("whitelist") && it.contains("stpanel") }); assertNotNull(vm.ui.value.error) }
    @Test fun updateClearsCacheOnSuccess() = runTest { vm.request(PanelAction.Update); vm.confirm(); advanceUntilIdle(); assertEquals(1, updatedCalls); assertTrue(vm.ui.value.restartSuggested) }
    @Test fun shellFailureShowsErrorAndReenablesActions() = runTest { shell.failWith(SshError.Unreachable("no route")); vm.request(PanelAction.Backup); advanceUntilIdle(); assertNull(vm.ui.value.busy); assertNotNull(vm.ui.value.error) }
    @Test fun secondActionIgnoredWhileBusy() = runTest { shell.delayMs = 1_000; vm.request(PanelAction.Backup); vm.request(PanelAction.Restart); advanceUntilIdle(); assertEquals(1, shell.commands.count { it.contains("stpanel") }) }
    @Test fun pollsOnlyWhileVisible() = runTest { vm.setVisible(true); advanceTimeBy(11_000); val n = shell.dashboardCalls; vm.setVisible(false); advanceTimeBy(20_000); assertEquals(n, shell.dashboardCalls); assertTrue(n in 2..3) }

    // --- Rulings and extra coverage ---

    @Test fun confirmActionsWaitForConfirm() = runTest {
        for (a in listOf(PanelAction.Stop, PanelAction.CleanCache, PanelAction.Update)) {
            vm.request(a); assertEquals(a, vm.ui.value.pendingConfirm); vm.dismiss(); assertNull(vm.ui.value.pendingConfirm)
        }
        advanceUntilIdle()
        assertTrue(shell.commands.none { it.contains("stpanel") })
    }

    @Test fun actionSetsBusyThenResultAndRefreshesDashboard() = runTest {
        vm.request(PanelAction.Backup)
        assertEquals(PanelAction.Backup, vm.ui.value.busy)
        advanceUntilIdle()
        assertNull(vm.ui.value.busy)
        assertEquals(ActionResult.Ok("Backup created, 12M"), vm.ui.value.lastResult)
        assertEquals(listOf("stpanel backup", DASHBOARD_CMD), shell.commands)
        assertEquals(9824, vm.ui.value.dashboard?.pid)
    }

    @Test fun timeoutsAre300sForUpdateAndBackupElse60s() = runTest {
        vm.request(PanelAction.Backup); advanceUntilIdle()
        vm.request(PanelAction.Update); vm.confirm(); advanceUntilIdle()
        vm.request(PanelAction.Restart); advanceUntilIdle()
        vm.request(PanelAction.CleanCache); vm.confirm(); advanceUntilIdle()
        assertEquals(300_000L, shell.timeoutFor("stpanel backup"))
        assertEquals(300_000L, shell.timeoutFor("stpanel update"))
        assertEquals(60_000L, shell.timeoutFor("stpanel restart"))
        assertEquals(60_000L, shell.timeoutFor("stpanel clean"))
    }

    @Test fun updateAlreadyUpToDateNeitherRevalidatesNorSuggestsRestart() = runTest {
        shell.updateOutput = "Already up to date.\n"
        vm.request(PanelAction.Update); vm.confirm(); advanceUntilIdle()
        assertEquals(0, updatedCalls)
        assertFalse(vm.ui.value.restartSuggested)
        assertEquals(ActionResult.Ok("Already up to date"), vm.ui.value.lastResult)
    }

    @Test fun whitelistToggleFeedsOneAndSuggestsRestart() = runTest {
        vm.loadWhitelist(); advanceUntilIdle()
        vm.toggleWhitelist(); vm.confirmWhitelistToggle(); advanceUntilIdle()
        assertEquals("1\n", shell.stdinFor("stpanel whitelist"))
        assertTrue(vm.ui.value.restartSuggested)
        assertNotNull(vm.ui.value.whitelist)
    }

    @Test fun restartClearsRestartSuggestion() = runTest {
        vm.addWhitelistEntry("10.0.0.1"); advanceUntilIdle()
        assertTrue(vm.ui.value.restartSuggested)
        vm.request(PanelAction.Restart); advanceUntilIdle()
        assertFalse(vm.ui.value.restartSuggested)
    }

    @Test fun restartReloadsChatOnceWhenDashboardShowsOnline() = runTest {
        shell.dashboards = mutableListOf(OFFLINE, OFFLINE, ONLINE)
        vm.request(PanelAction.Restart)
        advanceUntilIdle()
        assertEquals(1, restartedCalls)
        assertTrue(shell.dashboardCalls >= 3)
        // Later polls showing online don't reload again.
        vm.setVisible(true); advanceTimeBy(11_000); vm.setVisible(false)
        assertEquals(1, restartedCalls)
    }

    @Test fun restartWaitsForServerBeforeReloadingChat() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Start)
        advanceUntilIdle()
        assertEquals(0, restartedCalls)
    }

    @Test fun stopAndBackupDontReloadChat() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Stop); vm.confirm(); advanceUntilIdle()
        vm.request(PanelAction.Backup); advanceUntilIdle()
        assertEquals(0, restartedCalls)
    }

    @Test fun invalidUserDirShowsErrorInsteadOfCrashing() = runTest {
        probes.invalidUserDir = true
        vm.refresh(); advanceUntilIdle()
        assertNotNull(vm.ui.value.error)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun hostKeyChangedIsFlagged() = runTest {
        shell.failWith(SshError.HostKeyChanged("SHA256:old", "SHA256:new"))
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.ui.value.hostKeyChanged)
        assertNotNull(vm.ui.value.error)
    }

    @Test fun successfulRefreshClearsError() = runTest {
        shell.failWith(SshError.Unreachable("no route"))
        vm.refresh(); advanceUntilIdle()
        assertNotNull(vm.ui.value.error)
        shell.failWith(null)
        vm.refresh(); advanceUntilIdle()
        assertNull(vm.ui.value.error)
        assertTrue(vm.ui.value.dashboard!!.online)
    }

    @Test fun actionSurvivesHidingThePanel() = runTest {
        shell.delayMs = 1_000
        vm.setVisible(true)
        vm.request(PanelAction.Backup)
        vm.setVisible(false)
        advanceUntilIdle()
        assertEquals(ActionResult.Ok("Backup created, 12M"), vm.ui.value.lastResult)
    }

    @Test fun whitelistBlocksActionsWhileRunning() = runTest {
        shell.delayMs = 1_000
        vm.addWhitelistEntry("10.0.0.1")
        vm.request(PanelAction.Restart)
        advanceUntilIdle()
        assertTrue(shell.commands.none { it == "stpanel restart" })
    }

    // --- Fix round 1 ---

    @Test fun restartIgnoresStaleDashboardWhenProbesFail() = runTest {
        shell.dashboards = mutableListOf(ONLINE, FAIL, FAIL, ONLINE)
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.ui.value.dashboard!!.serving) // pre-restart state
        vm.request(PanelAction.Restart); advanceUntilIdle()
        assertEquals(1, restartedCalls)
        assertEquals(4, dashboardCallsAtRestart) // fired after the first successful post-restart probe, not on stale state
    }

    @Test fun awaitServingGivesUpAfterAbout90s() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Start); advanceUntilIdle()
        assertEquals(0, restartedCalls)
        // 1 post-action refresh + one probe every 2 s for 90 s.
        assertEquals(1 + 45, shell.dashboardCalls)
        assertTrue(currentTime in 88_000..92_000)
    }

    @Test fun awaitServingPausesWhileBackgrounded() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Restart); runCurrent()
        val n = shell.dashboardCalls
        vm.setAppForeground(false)
        advanceTimeBy(120_000)
        assertEquals(n, shell.dashboardCalls)
        shell.dashboards = mutableListOf(ONLINE)
        vm.setAppForeground(true); advanceUntilIdle()
        assertEquals(1, restartedCalls)
    }

    @Test fun whitelistLoadErrorIsShownInsteadOfSpinner() = runTest {
        shell.whitelists = mutableListOf(FAIL, WL_OFF)
        vm.loadWhitelist(); advanceUntilIdle()
        assertNull(vm.ui.value.whitelist)
        assertFalse(vm.ui.value.whitelistLoading)
        assertNotNull(vm.ui.value.whitelistError)
        vm.loadWhitelist(); advanceUntilIdle() // Retry
        assertNull(vm.ui.value.whitelistError)
        assertNotNull(vm.ui.value.whitelist)
    }

    @Test fun whitelistToggleNeedsConfirmation() = runTest {
        vm.loadWhitelist(); advanceUntilIdle()
        vm.toggleWhitelist(); advanceUntilIdle()
        assertEquals(true, vm.ui.value.pendingWhitelistToggle?.enable)
        assertTrue(shell.commands.none { it == "stpanel whitelist" })
        vm.dismissWhitelistToggle(); advanceUntilIdle()
        assertNull(vm.ui.value.pendingWhitelistToggle)
        assertTrue(shell.commands.none { it == "stpanel whitelist" })
    }

    @Test fun whitelistToggleIgnoredWhileLoading() = runTest {
        vm.toggleWhitelist() // nothing loaded yet
        assertNull(vm.ui.value.pendingWhitelistToggle)
        vm.loadWhitelist(); advanceUntilIdle()
        shell.delayMs = 1_000
        vm.loadWhitelist() // reloading: shown state may be stale
        vm.toggleWhitelist()
        vm.confirmWhitelistToggle()
        advanceUntilIdle()
        assertNull(vm.ui.value.pendingWhitelistToggle)
        assertTrue(shell.commands.none { it == "stpanel whitelist" })
    }

    @Test fun whitelistToggleAbortsIfModeChangedMeanwhile() = runTest {
        shell.whitelists = mutableListOf(WL_OFF, WL_ON)
        vm.loadWhitelist(); advanceUntilIdle()
        vm.toggleWhitelist() // dialog showed OFF, so target is ON
        vm.confirmWhitelistToggle(); advanceUntilIdle()
        assertTrue(shell.commands.none { it == "stpanel whitelist" })
        assertEquals(true, vm.ui.value.whitelist?.enabled)
        assertNotNull(vm.ui.value.whitelistMessage)
        assertFalse(vm.ui.value.restartSuggested)
    }

    @Test fun whitelistTurnOffWarnsAboutOpenAccess() = runTest {
        shell.whitelists = mutableListOf(WL_ON)
        vm.loadWhitelist(); advanceUntilIdle()
        vm.toggleWhitelist()
        val p = vm.ui.value.pendingWhitelistToggle!!
        assertFalse(p.enable)
        assertTrue(p.warning.contains("Anyone who can reach the server"))
        assertTrue(p.warning.contains("refuses to start"))
    }

    @Test fun whitelistTurnOnWarnsWhenLastClientNotCovered() = runTest {
        vm.refresh(); advanceUntilIdle() // last client 100.101.102.104
        vm.loadWhitelist(); advanceUntilIdle() // only 127.0.0.1
        vm.toggleWhitelist()
        val p = vm.ui.value.pendingWhitelistToggle!!
        assertTrue(p.enable)
        assertTrue(p.warning.contains("100.101.102.104"))
        vm.dismissWhitelistToggle()
        shell.whitelists = mutableListOf("mode=false\nentry=100.64.0.0/10\n")
        vm.loadWhitelist(); advanceUntilIdle()
        vm.toggleWhitelist()
        assertFalse(vm.ui.value.pendingWhitelistToggle!!.warning.contains("100.101.102.104"))
    }

    @Test fun coveredByMatchesExactAndIpv4Cidr() {
        assertTrue(isCoveredBy("100.101.102.104", listOf("100.64.0.0/10")))
        assertTrue(isCoveredBy("100.101.102.104", listOf("100.101.102.104")))
        assertTrue(isCoveredBy("::1", listOf("::1")))
        assertFalse(isCoveredBy("100.101.102.104", listOf("127.0.0.1", "192.168.0.0/16")))
        assertFalse(isCoveredBy("100.101.102.104", emptyList()))
    }

    @Test fun pollsPauseWhileActionRuns() = runTest {
        shell.actionDelayMs = 20_000
        vm.setVisible(true); runCurrent()
        assertEquals(1, shell.dashboardCalls)
        vm.request(PanelAction.Backup)
        advanceTimeBy(19_000)
        assertEquals(1, shell.dashboardCalls) // no polls under the running backup
        advanceTimeBy(1_500)
        // The action's own post-refresh, then possibly the poll that was due at the same instant.
        assertEquals(listOf(DASHBOARD_CMD, "stpanel backup", DASHBOARD_CMD), shell.commands.take(3))
        assertTrue(shell.dashboardCalls in 2..3)
        advanceTimeBy(10_000)
        assertTrue(shell.dashboardCalls >= 3) // polling resumed
        vm.setVisible(false)
    }

    // --- Fix round 2 ---

    @Test fun stopSuccessMarksServerDownOnce() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Stop); vm.confirm(); advanceUntilIdle()
        assertEquals(listOf("SillyTavern is stopped"), downReasons)
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, downReasons.size)
    }

    @Test fun dashboardGoingDownNotifiesOnceNotEveryPoll() = runTest {
        shell.dashboards = mutableListOf(ONLINE, OFFLINE)
        vm.setVisible(true); advanceTimeBy(21_000); vm.setVisible(false)
        assertTrue(shell.dashboardCalls >= 4)
        assertEquals(listOf("SillyTavern is stopped"), downReasons)
        assertEquals(0, restartedCalls)
    }

    @Test fun aliveButNotAnsweringNeverMarksDown() = runTest {
        // A busy server (e.g. streaming a generation) can miss the 1 s curl; reloading Chat would kill it.
        shell.dashboards = mutableListOf(ONLINE, NO_HTTP, NO_HTTP, NO_HTTP, ONLINE)
        repeat(5) { vm.refresh(); advanceUntilIdle() }
        assertTrue(downReasons.isEmpty())
        assertEquals(0, restartedCalls) // and no reload when it answers again
        assertEquals("200", vm.ui.value.dashboard?.httpCode) // the health card followed every poll
    }

    @Test fun twoHttpFailuresWithProcessAliveDontMarkDown() = runTest {
        shell.dashboards = mutableListOf(NO_HTTP, NO_HTTP)
        vm.refresh(); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertTrue(downReasons.isEmpty())
        assertEquals("000", vm.ui.value.dashboard?.httpCode)
        assertTrue(vm.ui.value.dashboard!!.online)
    }

    @Test fun processGoneMarksDownEvenWithoutStop() = runTest {
        shell.dashboards = mutableListOf(ONLINE, OFFLINE)
        vm.refresh(); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertEquals(listOf("SillyTavern is stopped"), downReasons)
    }

    @Test fun serverBackAfterObservedStopReloadsChatOnce() = runTest {
        // Started outside the app (e.g. stpanel over SSH): the next serving poll still recovers Chat.
        shell.dashboards = mutableListOf(ONLINE, OFFLINE, OFFLINE, ONLINE)
        vm.setVisible(true); advanceTimeBy(26_000); vm.setVisible(false)
        assertEquals(1, downReasons.size)
        assertEquals(1, restartedCalls)
        assertEquals(4, dashboardCallsAtRestart)
    }

    @Test fun startAfterStopReloadsChatOnce() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Stop); vm.confirm(); advanceUntilIdle()
        shell.dashboards = mutableListOf(OFFLINE, ONLINE)
        vm.request(PanelAction.Start); advanceUntilIdle()
        assertEquals(1, downReasons.size)
        assertEquals(1, restartedCalls)
    }

    @Test fun actionRequestedDuringPollRunsAfterIt() = runTest {
        shell.delayMs = 3_000 // every exec, so the poll is in flight for 3 s
        vm.setVisible(true); advanceTimeBy(1_000) // poll started, not finished
        assertEquals(1, shell.dashboardCalls)
        assertTrue(vm.ui.value.actionsEnabled) // buttons stay enabled during a poll
        vm.request(PanelAction.Backup)
        assertEquals(PanelAction.Backup, vm.ui.value.busy) // accepted, not ignored
        advanceTimeBy(1_000)
        assertTrue(shell.commands.none { it == "stpanel backup" }) // waits for the poll
        advanceTimeBy(2_000)
        assertEquals(listOf(DASHBOARD_CMD, "stpanel backup"), shell.commands.take(2))
        vm.setVisible(false); advanceUntilIdle()
        assertEquals(ActionResult.Ok("Backup created, 12M"), vm.ui.value.lastResult)
    }

    @Test fun lastActionStaysVisibleAfterSnackbar() = runTest {
        vm.request(PanelAction.Backup); advanceUntilIdle()
        vm.resultShown()
        assertNull(vm.ui.value.lastResult)
        assertEquals(ActionResult.Ok("Backup created, 12M"), vm.ui.value.lastAction)
    }

    // --- Fix round 3 ---

    @Test fun chatReadyAfterDownMeansNoReloadOnServingPoll() = runTest {
        // Stopped, restarted outside the app, user tapped Retry and Chat is Ready: don't reload a working chat.
        shell.dashboards = mutableListOf(OFFLINE, ONLINE)
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, downReasons.size)
        vm.onChatReady()
        vm.refresh(); advanceUntilIdle()
        assertEquals(0, restartedCalls)
    }

    @Test fun servingPollWhileChatStillUnreachableReloadsOnce() = runTest {
        shell.dashboards = mutableListOf(OFFLINE, ONLINE)
        vm.refresh(); advanceUntilIdle()
        repeat(3) { vm.refresh(); advanceUntilIdle() }
        assertEquals(1, restartedCalls)
    }

    @Test fun chatReadyCancelsPendingStartReload() = runTest {
        shell.dashboards = mutableListOf(OFFLINE)
        vm.request(PanelAction.Start); runCurrent()
        vm.onChatReady() // Chat recovered on its own (e.g. Retry) before the Panel saw the server serving
        shell.dashboards = mutableListOf(ONLINE)
        advanceUntilIdle()
        assertEquals(0, restartedCalls)
    }

    @Test fun restartSuccessMarksChatDownThenReloadsOnce() = runTest {
        // Even if the server is already serving by the post-refresh, the old page belongs to a dead process.
        vm.request(PanelAction.Restart); advanceUntilIdle()
        assertEquals(listOf("SillyTavern is restarting"), downReasons)
        assertEquals(1, restartedCalls)
    }
}
