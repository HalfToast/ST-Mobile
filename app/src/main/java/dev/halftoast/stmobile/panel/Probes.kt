package dev.halftoast.stmobile.panel

import android.content.Context
import dev.halftoast.stmobile.settings.ConnectionForm
import java.util.concurrent.ConcurrentHashMap

/**
 * What the app sends over SSH: a [command] line that any login shell (bash, zsh, fish) parses the
 * same way, `env NAME='value'… <interpreter> -s`, and the actual work on [stdin].
 */
data class RemoteCommand(val command: String, val stdin: String?)

enum class LogKind { Server, Access }

/** A folder in Settings that commands can't use; [message] says which, in words for the user. */
class InvalidFolderSetting(override val message: String) : IllegalArgumentException(message)

const val INVALID_ST_DIR = "The SillyTavern folder in Settings is invalid"
const val INVALID_ST_USER_DIR = "The SillyTavern user folder in Settings is invalid"

/** Builds the [RemoteCommand]s for the probe scripts in assets/probes, the stpanel actions and the log tails. */
object Probes {
    fun dashboard(ctx: Context, stDir: String, userDir: String): RemoteCommand =
        dashboardWith(readAsset(ctx, "probes/dashboard.sh"), stDir, userDir)

    fun stats(ctx: Context, stDir: String, userDir: String): RemoteCommand =
        statsWith(readAsset(ctx, "probes/stats.py"), stDir, userDir)

    fun whitelist(ctx: Context, stDir: String): RemoteCommand =
        whitelistWith(readAsset(ctx, "probes/whitelist.sh"), stDir)

    // The *With variants take the script text instead of a Context (tests).
    internal fun dashboardWith(script: String, stDir: String, userDir: String) = build("bash -s", stDir, userDir, script)

    internal fun statsWith(script: String, stDir: String, userDir: String) = build("python3 -", stDir, userDir, script)

    internal fun whitelistWith(script: String, stDir: String) = RemoteCommand(envCommand("bash -s", stDirVar(stDir)), script)

    /** Runs `stpanel <sub>` detached (see [wrapDetached]) with [input] on its stdin. */
    fun action(ctx: Context, sub: String, stDir: String, input: String?): RemoteCommand =
        actionWith(readAsset(ctx, "stpanel"), sub, stDir, input)

    /**
     * Like [action], with the built-in copy's text given: an installed `stpanel` (on PATH) wins;
     * otherwise [stpanel] runs from a temp file (its process matching relies on its own arguments,
     * so never `bash -c "<text>"`).
     */
    fun actionWith(stpanel: String, sub: String, stDir: String, input: String?): RemoteCommand {
        require(SUBCOMMAND.matches(sub)) { "bad stpanel subcommand" }
        // ST_DIR (when set) comes from the command line's env, so it is exported to either stpanel.
        val cmd = "if command -v stpanel >/dev/null 2>&1; then stpanel $sub; else bash \"\$d/stpanel\" $sub; fi"
        return RemoteCommand(envCommand("sh -s", stDirVar(stDir)), wrapDetached(cmd, marker = sub, input = input, files = mapOf("stpanel" to stpanel)))
    }

    /** `tail -F` of the server log or the access log, in the folder [stDir] names (or the default one). */
    fun tail(kind: LogKind, stDir: String): RemoteCommand {
        val file = when (kind) {
            LogKind.Server -> "sillytavern.log"
            LogKind.Access -> "data/access.log"
        }
        return RemoteCommand(envCommand("sh -s", stDirVar(stDir)), RESOLVE_ST_DIR + "exec tail -n 200 -F \"\$dir/$file\"\n")
    }

    /** ST folder resolution, as everywhere: ST_DIR (leading `~/` expanded), else Termux's, else `$HOME/SillyTavern`. */
    private val RESOLVE_ST_DIR = """
        if [ -n "${'$'}{ST_DIR:-}" ]; then
            case "${'$'}ST_DIR" in
                "~/"*) dir="${'$'}HOME/${'$'}{ST_DIR#"~/"}" ;;
                *) dir=${'$'}ST_DIR ;;
            esac
        elif [ -d /data/data/com.termux/files/home/SillyTavern ]; then
            dir=/data/data/com.termux/files/home/SillyTavern
        else
            dir="${'$'}HOME/SillyTavern"
        fi

    """.trimIndent()

    private val SUBCOMMAND = Regex("[a-z]+")
    private val FILE_NAME = Regex("[A-Za-z0-9_-]+")
    private val SAFE_COMMAND = Regex("""env( [A-Z_]+='[^'\\\n]*')* (bash|sh|python3) (-s|-)""")

    /** True for the only command shapes the app sends: `env NAME='value'… bash|sh|python3 -s|-`, or `echo ok`. */
    fun isSafeCommand(command: String): Boolean = command == "echo ok" || SAFE_COMMAND.matches(command)

    /** Where [wrapDetached] marks running actions for dashboard.sh (`action=`), under the tablet's TMPDIR. */
    const val ACTION_MARKS = "stmobile-actions"

    /** dashboard.sh's copy of its slow lines (counts, git), under TMPDIR; a marked action deletes it when it ends. */
    const val DASHBOARD_CACHE = "stmobile-dashboard.slow"

    /**
     * A script for `sh -s` that runs [cmd] in the background with its stdin, stdout and stderr in a
     * temp dir `$d`, then replays them and its exit code. If the channel closes first (action timeout,
     * app killed), nothing writes to the dead pipe, so SIGPIPE can't kill stpanel, git or npm halfway;
     * the command finishes and the temp dir is still removed (the wrapper ignores SIGPIPE, the command doesn't).
     *
     * The script is one `{ … }` block, so the shell has read all of it before anything runs: a channel
     * closing mid-run can't cut off its cleanup. [input] becomes the command's stdin and each of [files]
     * becomes `$d/<name>`, both from quoted heredocs in the script (a missing final newline is added).
     *
     * With a [marker] (`[a-z]+`), a file `<pid> <marker>` in [ACTION_MARKS] says the command is running
     * until it ends, even after the app gave up waiting. Written via rename so a reader never sees it
     * half-written; removed by the command's subshell and again by the wrapper (whichever lives);
     * dashboard.sh drops one whose process is gone. Its cached counts and git state go at the same time.
     * The marked command's own output is never cached: dashboard.sh doesn't cache while one runs.
     */
    fun wrapDetached(cmd: String, marker: String?, input: String?, files: Map<String, String> = emptyMap()): String {
        require(marker == null || SUBCOMMAND.matches(marker)) { "bad marker" }
        require(files.keys.all { FILE_NAME.matches(it) && it != "in" && it != "out" && it != "err" && it != "rc" }) { "bad file name" }
        var n = 0
        fun heredoc(name: String, content: String): String {
            while ("STM_EOF_$n" in content) n++
            val delimiter = "STM_EOF_${n++}"
            val body = if (content.isEmpty() || content.endsWith("\n")) content else content + "\n"
            return "cat >\"\$d/$name\" <<'$delimiter'\n$body$delimiter"
        }
        val lines = mutableListOf("{", "d=$(mktemp -d) || exit 1", "t=")
        if (marker != null) {
            lines += """c="${'$'}{TMPDIR:-/tmp}/$DASHBOARD_CACHE"; m="${'$'}{TMPDIR:-/tmp}/$ACTION_MARKS"; mkdir -p "${'$'}m" && t="${'$'}m/${'$'}{d##*/}""""
        }
        lines += if (input == null) ": >\"\$d/in\"" else heredoc("in", input)
        files.forEach { (name, content) -> lines += heredoc(name, content) }
        lines += "( trap '' HUP; ( $cmd"
        lines += """) <"${'$'}d/in" >"${'$'}d/out" 2>"${'$'}d/err"; echo ${'$'}? >"${'$'}d/rc"; [ -z "${'$'}t" ] || rm -f "${'$'}c" "${'$'}t" ) </dev/null >/dev/null 2>&1 &"""
        if (marker != null) {
            lines += """[ -z "${'$'}t" ] || { echo "${'$'}! $marker" >"${'$'}t.w" && mv "${'$'}t.w" "${'$'}t"; } 2>/dev/null"""
        }
        lines += listOf(
            "trap '' PIPE",
            "wait \$!",
            """[ -z "${'$'}t" ] || rm -f "${'$'}c" "${'$'}t"""",
            """cat "${'$'}d/out"; cat "${'$'}d/err" >&2""",
            """rc=$(cat "${'$'}d/rc" 2>/dev/null); [ -n "${'$'}rc" ] || rc=1""",
            """rm -rf "${'$'}d"""",
            """exit "${'$'}rc"""",
            "}",
        )
        return lines.joinToString("\n", postfix = "\n")
    }

    /** Re-checks the (untrusted, possibly pre-validation) folders and puts them in the command line. */
    private fun build(interpreter: String, stDir: String, userDir: String, script: String): RemoteCommand {
        if (!ConnectionForm.isValidStUserDir(userDir)) throw InvalidFolderSetting(INVALID_ST_USER_DIR)
        return RemoteCommand(envCommand(interpreter, stDirVar(stDir) + ("ST_USER" to userDir)), script)
    }

    private fun stDirVar(stDir: String): List<Pair<String, String>> {
        if (!ConnectionForm.isValidStDir(stDir)) throw InvalidFolderSetting(INVALID_ST_DIR)
        return if (stDir.isEmpty()) emptyList() else listOf("ST_DIR" to stDir)
    }

    private fun envCommand(interpreter: String, vars: List<Pair<String, String>>): String {
        val command = "env" + vars.joinToString("") { (name, value) -> " $name='$value'" } + " $interpreter"
        check(isSafeCommand(command)) { "unsafe command" }
        return command
    }

    // Read once: probes poll every few seconds, from the main thread.
    private val assetCache = ConcurrentHashMap<String, String>()

    private fun readAsset(ctx: Context, path: String): String =
        assetCache.getOrPut(path) { ctx.assets.open(path).bufferedReader().use { it.readText() } }
}
