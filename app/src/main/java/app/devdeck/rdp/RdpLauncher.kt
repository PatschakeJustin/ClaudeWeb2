package app.devdeck.rdp

import android.content.Context
import android.content.Intent
import app.devdeck.core.model.RdpConnection
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ManualBookmark
import com.freerdp.freerdpcore.presentation.SessionActivity

/** Built-in RDP client (FreeRDP). Opens the remote desktop inside the app – no extra app needed. */
object RdpLauncher {
    private var loaded: Boolean? = null

    /**
     * True when the native RDP libraries are part of this APK for this device's CPU. Checked by loading them:
     * modern Android can run libraries directly from the APK, so they are not always unpacked as files.
     */
    @Synchronized
    fun available(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = loaded ?: runCatching {
        System.loadLibrary("freerdp-android"); true
    }.getOrElse { false }.also { loaded = it }

    fun open(context: Context, c: RdpConnection, passwordOverride: String? = null) {
        GlobalApp.init(context)
        val b = ManualBookmark()
        b.setLabel(c.name.ifBlank { c.host })
        b.setHostname(c.host)
        b.setPort(c.port)
        b.setUsername(c.username)
        b.setPassword(passwordOverride ?: c.password)
        b.setDomain(c.domain)
        b.getScreenSettings().setResolution(if (c.fitScreen) BookmarkBase.ScreenSettings.FITSCREEN else BookmarkBase.ScreenSettings.AUTOMATIC)
        b.getScreenSettings().setColors(32)
        b.getAdvancedSettings().setConsoleMode(c.console)
        // Handed over in memory only – the password is not written into FreeRDP's own database.
        GlobalApp.pendingBookmark = b
        context.startActivity(Intent(context, SessionActivity::class.java)
            .putExtra(SessionActivity.PARAM_PENDING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
