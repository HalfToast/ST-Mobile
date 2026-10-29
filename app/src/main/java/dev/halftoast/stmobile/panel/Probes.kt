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
     * Pass-through: recon showed `stpanel restart` already survives the SSH session ending
     * (startst daemonises), so no setsid/nohup wrapper is needed. Single place to change this later.
     */
    fun wrapDetached(cmd: String): String = cmd

    /** Re-checks the (untrusted, possibly pre-validation) user dir and single-quotes it. */
    internal fun build(asset: String, userDir: String, interpreter: String, load: (String) -> String): Pair<String, String> {
        require(ConnectionForm.isValidStUserDir(userDir)) { "Invalid SillyTavern user folder" }
        return "ST_USER='$userDir' $interpreter" to load(asset)
    }

    private fun readAsset(ctx: Context, name: String): String =
        ctx.assets.open("probes/$name").bufferedReader().use { it.readText() }
}
