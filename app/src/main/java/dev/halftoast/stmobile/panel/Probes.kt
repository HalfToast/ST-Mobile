package dev.halftoast.stmobile.panel

import android.content.Context
import dev.halftoast.stmobile.settings.ConnectionForm

/** Builds `(command, stdin)` pairs for the read-only probe scripts in assets/probes. */
object Probes {
    const val TAIL_SERVER_LOG = "tail -n 200 -F \"\$HOME/SillyTavern/sillytavern.log\""
    const val TAIL_ACCESS_LOG = "tail -n 200 -F \"\$HOME/SillyTavern/data/access.log\""

    fun dashboard(ctx: Context, userDir: String): Pair<String, String> =
        build("dashboard.sh", userDir, "bash -s") { readAsset(ctx, it) }

    fun stats(ctx: Context, userDir: String): Pair<String, String> =
        build("stats.py", userDir, "python3 -") { readAsset(ctx, it) }

    fun whitelist(ctx: Context): Pair<String, String> = "bash -s" to readAsset(ctx, "whitelist.sh")

    fun action(sub: String): String = wrapDetached("stpanel $sub")

    /**
     * Runs [cmd] in the background with its stdin, stdout and stderr in a temp dir, then replays
     * them and its exit code. If the channel closes first (action timeout, app killed), nothing
     * writes to the dead pipe, so SIGPIPE can't kill stpanel, git or npm halfway; the command
     * finishes and the temp dir is still removed (the wrapper ignores SIGPIPE, the command doesn't).
     */
    fun wrapDetached(cmd: String): String = """
        d=$(mktemp -d) || exit 1
        cat >"${'$'}d/in"
        ( trap '' HUP; ( $cmd
        ) <"${'$'}d/in" >"${'$'}d/out" 2>"${'$'}d/err"; echo ${'$'}? >"${'$'}d/rc" ) </dev/null >/dev/null 2>&1 &
        trap '' PIPE
        wait ${'$'}!
        cat "${'$'}d/out"; cat "${'$'}d/err" >&2
        rc=$(cat "${'$'}d/rc" 2>/dev/null); [ -n "${'$'}rc" ] || rc=1
        rm -rf "${'$'}d"
        exit "${'$'}rc"
    """.trimIndent()

    /** Re-checks the (untrusted, possibly pre-validation) user dir and single-quotes it. */
    internal fun build(asset: String, userDir: String, interpreter: String, load: (String) -> String): Pair<String, String> {
        require(ConnectionForm.isValidStUserDir(userDir)) { "Invalid SillyTavern user folder" }
        return "ST_USER='$userDir' $interpreter" to load(asset)
    }

    private fun readAsset(ctx: Context, name: String): String =
        ctx.assets.open("probes/$name").bufferedReader().use { it.readText() }
}
