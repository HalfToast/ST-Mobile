package dev.halftoast.stmobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.halftoast.stmobile.settings.AppSettings
import dev.halftoast.stmobile.ssh.AndroidKeystoreWrapper
import dev.halftoast.stmobile.ssh.KeyManager
import dev.halftoast.stmobile.ssh.SshRemoteShell
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class StMobileApp : Application() {
    companion object {
        const val LOG_TAG = "STMobile"
        const val KEEPALIVE_CHANNEL_ID = "keepalive"
    }

    val settings: AppSettings by lazy { AppSettings(getSharedPreferences("settings", Context.MODE_PRIVATE)) }

    /** The app's SSH identity. noBackupFilesDir: the wrapping key in Keystore never leaves the device anyway. */
    val keyManager: KeyManager by lazy { KeyManager(noBackupFilesDir, AndroidKeystoreWrapper()) }

    /** One shared SSH connection to the tablet for every screen. */
    val remoteShell: SshRemoteShell by lazy { SshRemoteShell(settings, keyManager) }

    override fun onCreate() {
        super.onCreate()
        // Android's built-in "BC" is a stripped-down BouncyCastle; sshj needs the full provider.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        val channel = NotificationChannel(
            KEEPALIVE_CHANNEL_ID, "Keep alive", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps ST Mobile loaded in the background" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
