package moe.shizuku.manager.adb

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku
import java.security.SecureRandom

/** Wireless debugging over Bass Helper's own adb key ("basshelper"). */
object WirelessAdb {

    private const val MDNS_TIMEOUT_MS = 15_000L
    private const val BINDER_TIMEOUT_MS = 10_000L

    fun key() = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "basshelper")

    /** Needs WRITE_SECURE_SETTINGS. */
    fun enable(cr: ContentResolver) {
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
    }

    suspend fun discoverPort(context: Context, serviceType: String, timeoutMs: Long = MDNS_TIMEOUT_MS): Int? {
        val found = CompletableDeferred<Int>()
        val mdns = AdbMdns(context, serviceType) { port -> if (port > 0) found.complete(port) }
        mdns.start()
        return try {
            withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            mdns.stop()
        }
    }

    private fun connects(port: Int): Boolean = try {
        AdbClient("127.0.0.1", port, key()).use { it.connect() }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Pairs Bass Helper's key with wireless debugging so it can start itself after a reboot.
     * Asks adbd, through the running server (the shell holds MANAGE_DEBUGGING), to accept a pairing
     * with a password chosen here, the same way Android Studio's QR pairing works, so no one
     * has to read a code off the screen. Returns a short status for logs and deployers.
     */
    suspend fun pairSelf(context: Context): String {
        if (!awaitBinder()) return "failed: Bass Helper server is not running"
        try {
            enable(context.contentResolver)
        } catch (e: SecurityException) {
            return "failed: WRITE_SECURE_SETTINGS not granted"
        }
        val connectPort = discoverPort(context, AdbMdns.TLS_CONNECT)
            ?: return "failed: wireless debugging is not available (no Wi-Fi?)"
        if (connects(connectPort)) return "already_paired"

        val password = randomString(10)
        try {
            adbServiceCall("enablePairingByQrCode", "basshelper-" + randomString(6), password)
            val pairingPort = discoverPort(context, AdbMdns.TLS_PAIRING)
                ?: return "failed: pairing port not advertised"
            val paired = AdbPairingClient("127.0.0.1", pairingPort, password, key()).use { it.start() }
            if (!paired) return "failed: pairing rejected"
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "Self pairing failed", e)
            return "failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                adbServiceCall("disablePairing")
            } catch (e: Exception) {
                Log.w(AppConstants.TAG, "disablePairing", e)
            }
        }
        return if (connects(connectPort)) "paired" else "failed: paired but the key is not accepted"
    }

    /**
     * Apps cannot look up the adb service, so this runs `service call adb` in the server
     * (shell user). Transaction codes come from the device's own IAdbManager stub.
     */
    private fun adbServiceCall(method: String, vararg args: String) {
        val code = Class.forName("android.debug.IAdbManager\$Stub")
            .getDeclaredField("TRANSACTION_$method")
            .apply { isAccessible = true }
            .getInt(null)
        val cmd = arrayOf("service", "call", "adb", code.toString()) + args.flatMap { listOf("s16", it) }
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val process = newProcess.invoke(null, cmd, null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (!output.contains(Regex("""Parcel\(\s*00000000"""))) error("service call adb $method: ${output.trim()}")
    }

    private suspend fun awaitBinder(): Boolean {
        if (Shizuku.pingBinder()) return true
        val received = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { received.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener)
        return try {
            withTimeoutOrNull(BINDER_TIMEOUT_MS) { received.await() } != null && Shizuku.pingBinder()
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    private fun randomString(length: Int): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }
}
