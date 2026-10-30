package dev.halftoast.stmobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.halftoast.stmobile.panel.ActionTracker
import dev.halftoast.stmobile.settings.AppSettings
import dev.halftoast.stmobile.settings.legacyServerUrl
import dev.halftoast.stmobile.ssh.AndroidKeystoreWrapper
import dev.halftoast.stmobile.ssh.KeyManager
import dev.halftoast.stmobile.ssh.SshRemoteShell
import dev.halftoast.stmobile.stats.AvatarCache
import dev.halftoast.stmobile.stats.httpAvatarFetch
import dev.halftoast.stmobile.ui.ThemeStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.Security

class StMobileApp : Application() {
    companion object {
        const val LOG_TAG = "STMobile"
        const val KEEPALIVE_CHANNEL_ID = "keepalive"
        private const val AVATAR_DP = 40
    }

    val settings: AppSettings by lazy { AppSettings(getSharedPreferences("settings", Context.MODE_PRIVATE)) }

    /** The app's SSH identity. noBackupFilesDir: the wrapping key in Keystore never leaves the device anyway. */
    val keyManager: KeyManager by lazy { KeyManager(noBackupFilesDir, AndroidKeystoreWrapper()) }

    /** One shared SSH connection to the server for every screen. */
    private val remoteShellLazy = lazy { SshRemoteShell(settings, keyManager) }
    val remoteShell: SshRemoteShell by remoteShellLazy

    /** Lives as long as the process: Control Center actions run here so nothing UI-bound can cancel them. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val actions: ActionTracker by lazy { ActionTracker(appScope) }

    /** The SillyTavern theme the native screens follow; persisted so it applies before the web UI loads. */
    val themeStore by lazy { ThemeStore({ settings.stThemeJson }, { settings.stThemeJson = it }) }

    /** Character avatars fetched from ST's thumbnail endpoint, shared by every screen. */
    val avatars by lazy {
        // Decoded at about twice the 40 dp avatar circle.
        val targetPx = (2 * AVATAR_DP * resources.displayMetrics.density).toInt()
        AvatarCache(200, httpAvatarFetch(targetPx) { settings.serverUrl })
    }

    override fun onCreate() {
        super.onCreate()
        // Installs from before the built-in address keep opening straight to Chat.
        legacyServerUrl(settings.hasServerUrl, settings.setupComplete, settings.sshHost)?.let { settings.serverUrl = it }
        // Android's built-in "BC" is a stripped-down BouncyCastle; sshj needs the full provider.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        val channel = NotificationChannel(
            KEEPALIVE_CHANNEL_ID, "Keep alive", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps ST Mobile loaded in the background" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // Whole app in the background: drop the idle SSH connection (kept if an action or stream runs).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (remoteShellLazy.isInitialized()) remoteShell.onAppBackground()
            }
        })
    }
}
