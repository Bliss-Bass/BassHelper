package rikka.shizuku.server

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemClock
import android.util.Log

/**
 * Exits the server when its main thread stalls or a core system service dies (a system_server
 * restart leaves this process holding dead binders); the starter's supervisor then starts a fresh
 * server, which waits for those services. Adapted from NightDog in Nightzuku
 * (https://github.com/kerneldroid/Nightzuku, Apache-2.0), which rebinds in place instead.
 */
object ServerWatchdog {

    private const val TAG = "ServerWatchdog"
    private const val HEARTBEAT_MS = 5_000L
    private const val CHECK_MS = 15_000L
    private const val STALL_TIMEOUT_MS = 60_000L

    private val CRITICAL_SERVICES = arrayOf("package", "activity", "user", "appops")

    @Volatile
    private var lastBeat = 0L
    private var started = false

    @JvmStatic
    fun start(mainHandler: Handler) {
        if (started) return
        started = true

        lastBeat = SystemClock.uptimeMillis()
        mainHandler.post(object : Runnable {
            override fun run() {
                lastBeat = SystemClock.uptimeMillis()
                mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        })

        for (name in CRITICAL_SERVICES) {
            val binder: IBinder? = ServiceManager.getService(name)
            if (binder == null) {
                fail("system service '$name' is gone")
                return
            }
            try {
                binder.linkToDeath({ fail("system service '$name' died") }, 0)
            } catch (e: Exception) {
                fail("system service '$name' died")
                return
            }
        }

        val thread = HandlerThread("watchdog").apply {
            isDaemon = true
            start()
        }
        val handler = Handler(thread.looper)
        handler.postDelayed(object : Runnable {
            override fun run() {
                val stalled = SystemClock.uptimeMillis() - lastBeat
                if (stalled >= STALL_TIMEOUT_MS) {
                    Log.e(TAG, "main thread stack:\n" + Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" })
                    fail("main thread stalled for ${stalled}ms")
                    return
                }
                handler.postDelayed(this, CHECK_MS)
            }
        }, CHECK_MS)
        Log.i(TAG, "started")
    }

    private fun fail(reason: String) {
        Log.e(TAG, "$reason, exiting for the supervisor to restart the server")
        // halt: shutdown hooks could block on the stalled main thread.
        Runtime.getRuntime().halt(ServerConstants.EXIT_WATCHDOG)
    }
}
