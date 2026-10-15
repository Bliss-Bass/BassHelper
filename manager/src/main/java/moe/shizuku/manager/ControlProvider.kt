package moe.shizuku.manager

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.receiver.scheduleAdbStart
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.server.BassAllowList

/**
 * Lets allow-listed apps check on the server and ask for it to be started over wireless
 * debugging, e.g. SmartDock when its binder dies and the supervisor did not bring it back:
 * `content://com.blisscolabs.basshelper.control`, methods [METHOD_STATUS] and [METHOD_START].
 * A Stop in the app, or start on boot turned off, is respected.
 */
class ControlProvider : ContentProvider() {

    companion object {
        const val METHOD_STATUS = "status"
        const val METHOD_START = "start"
        private const val BINDER_WAIT_MS = 3_000L
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_STATUS && method != METHOD_START) return null
        if (!callerAllowed()) throw SecurityException("$method is limited to Bass apps")

        val running = awaitBinder()
        val status = bundleOf(
            "running" to running,
            "user_stopped" to ShizukuSettings.isUserStopped(),
            "start_on_boot" to startOnBootEnabled()
        )
        if (method == METHOD_STATUS) return status

        val result = when {
            running -> "running"
            ShizukuSettings.isUserStopped() -> "stopped_by_user"
            !startOnBootEnabled() -> "auto_start_disabled"
            else -> scheduleAdbStart(context!!)
        }
        LOGGER.i("control start from uid ${Binder.getCallingUid()}: $result")
        return status.apply { putString("result", result) }
    }

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        val appId = uid % 100000
        if (appId == Process.SHELL_UID || appId == Process.ROOT_UID || appId == Process.myUid() % 100000) return true
        return context!!.packageManager.getPackagesForUid(uid)?.any { BassAllowList.contains(it) } == true
    }

    private fun startOnBootEnabled() = context!!.packageManager.isComponentEnabled(
        ComponentName(context!!, BootCompleteReceiver::class.java)
    )

    // A call can start this process before the running server has sent it the binder again.
    private fun awaitBinder(): Boolean {
        if (Shizuku.pingBinder()) return true
        val received = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { received.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener)
        return try {
            runBlocking { withTimeoutOrNull(BINDER_WAIT_MS) { received.await() } } != null && Shizuku.pingBinder()
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
