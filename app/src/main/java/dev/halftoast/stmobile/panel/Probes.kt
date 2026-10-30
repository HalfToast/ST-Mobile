package dev.halftoast.stmobile.panel

import android.content.Context
import dev.halftoast.stmobile.settings.ConnectionForm
import java.util.concurrent.ConcurrentHashMap

/** Builds `(command, stdin)` pairs for the read-only probe scripts in assets/probes. */
object Probes {
    const val TAIL_SERVER_LOG = "tail -n 200 -F \"\$HOME/SillyTavern/sillytavern.log\""
    const val TAIL_ACCESS_LOG = "tail -n 200 -F \"\$HOME/SillyTavern/data/access.log\""

    fun dashboard(ctx: Context, userDir: String): Pair<String, String> =
        build("dashboard.sh", userDir, "bash -s") { readAsset(ctx, it) }

    fun stats(ctx: Context, userDir: String): Pair<String, String> =
        build("stats.py", userDir, "python3 -") { readAsset(ctx, it) }

    fun whitelist(ctx: Context): Pair<String, String> = "bash -s" to readAsset(ctx, "whitelist.sh")

    fun action(sub: String): String {
        require(SUBCOMMAND.matches(sub)) { "bad stpanel subcommand" }
        return wrapDetached("stpanel $sub", marker = sub)
    }

    private val SUBCOMMAND = Regex("[a-z]+")

    /** Where [wrapDetached] marks running actions for dashboard.sh (`action=`), under the tablet's TMPDIR. */
    const val ACTION_MARKS = "stmobile-actions"

    /** dashboard.sh's copy of its slow lines (counts, git), under TMPDIR; a marked action deletes it when it ends. */
    const val DASHBOARD_CACHE = "stmobile-dashboard.slow"

    /**
     * Runs [cmd] in the background with its stdin, stdout and stderr in a temp dir, then replays
     * them and its exit code. If the channel closes first (action timeout, app killed), nothing
     * writes to the dead pipe, so SIGPIPE can't kill stpanel, git or npm halfway; the command
     * finishes and the temp dir is still removed (the wrapper ignores SIGPIPE, the command doesn't).
     *
     * With a [marker] (`[a-z]+`), a file `<pid> <marker>` in [ACTION_MARKS] says the command is running
     * until it ends, even after the app gave up waiting. Written via rename so a reader never sees it
     * half-written; removed by the command's subshell and again by the wrapper (whichever lives);
     * dashboard.sh drops one whose process is gone. Its cached counts and git state go at the same time.
     * The marked command's own output is never cached: dashboard.sh doesn't cache while one runs.
     */
    fun wrapDetached(cmd: String, marker: String? = null): String {
        val markSetup = if (marker == null) "" else
            """c="${'$'}{TMPDIR:-/tmp}/$DASHBOARD_CACHE"; m="${'$'}{TMPDIR:-/tmp}/$ACTION_MARKS"; mkdir -p "${'$'}m" && t="${'$'}m/${'$'}{d##*/}""""
        val markWrite = if (marker == null) "" else
            """[ -z "${'$'}t" ] || { echo "${'$'}! $marker" >"${'$'}t.w" && mv "${'$'}t.w" "${'$'}t"; } 2>/dev/null"""
        return """
        d=$(mktemp -d) || exit 1
        t=
        $markSetup
        cat >"${'$'}d/in"
        ( trap '' HUP; ( $cmd
        ) <"${'$'}d/in" >"${'$'}d/out" 2>"${'$'}d/err"; echo ${'$'}? >"${'$'}d/rc"; [ -z "${'$'}t" ] || rm -f "${'$'}c" "${'$'}t" ) </dev/null >/dev/null 2>&1 &
        $markWrite
        trap '' PIPE
        wait ${'$'}!
        [ -z "${'$'}t" ] || rm -f "${'$'}c" "${'$'}t"
        cat "${'$'}d/out"; cat "${'$'}d/err" >&2
        rc=$(cat "${'$'}d/rc" 2>/dev/null); [ -n "${'$'}rc" ] || rc=1
        rm -rf "${'$'}d"
        exit "${'$'}rc"
        """.trimIndent()
    }

    /** Re-checks the (untrusted, possibly pre-validation) user dir and single-quotes it. */
    internal fun build(asset: String, userDir: String, interpreter: String, load: (String) -> String): Pair<String, String> {
        require(ConnectionForm.isValidStUserDir(userDir)) { "Invalid SillyTavern user folder" }
        return "ST_USER='$userDir' $interpreter" to load(asset)
    }

    // Read once: probes poll every few seconds, from the main thread.
    private val assetCache = ConcurrentHashMap<String, String>()

    private fun readAsset(ctx: Context, name: String): String =
        assetCache.getOrPut(name) { ctx.assets.open("probes/$name").bufferedReader().use { it.readText() } }
}
