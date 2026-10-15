package moe.shizuku.manager

import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.adb.WirelessAdb
import moe.shizuku.manager.receiver.scheduleAdbStart
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ShizukuManagerProvider : ShizukuProvider() {

    companion object {
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"

        /** `content call --user <id> --uri content://com.blisscolabs.basshelper.shizuku --method pairAdbKey` */
        private const val METHOD_PAIR_ADB_KEY = "pairAdbKey"
        /** Runs the start-on-boot path now: `--method scheduleStart`. */
        private const val METHOD_SCHEDULE_START = "scheduleStart"
        private const val PAIR_TIMEOUT_MS = 60_000L
        private const val SHELL_APP_ID = 2000
    }

    private fun enforceShell(method: String) {
        val appId = Binder.getCallingUid() % 100000
        if (appId != SHELL_APP_ID && appId != Process.ROOT_UID) {
            throw SecurityException("$method is limited to the shell user")
        }
    }

    // Deployers run as the shell user; no app may make Bass Helper add a trusted adb key.
    private fun pairAdbKey(): Bundle {
        enforceShell(METHOD_PAIR_ADB_KEY)
        val result = runBlocking {
            withTimeoutOrNull(PAIR_TIMEOUT_MS) { WirelessAdb.pairSelf(context!!) } ?: "failed: timed out"
        }
        LOGGER.i("pairAdbKey: $result")
        return bundleOf("result" to result)
    }

    private fun scheduleStart(): Bundle {
        enforceShell(METHOD_SCHEDULE_START)
        val result = scheduleAdbStart(context!!)
        LOGGER.i("scheduleStart: $result")
        return bundleOf("result" to result)
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_PAIR_ADB_KEY) return pairAdbKey()
        if (method == METHOD_SCHEDULE_START) return scheduleStart()
        if (extras == null) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                val countDownLatch = CountDownLatch(1)
                var reply: Bundle? = Bundle()

                val listener = object : Shizuku.OnBinderReceivedListener {

                    override fun onBinderReceived() {
                        try {
                            Shizuku.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token
                            ))
                            reply!!.putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder()))
                        } catch (e: Throwable) {
                            LOGGER.e(e, "attachUserService $token")
                            reply = null
                        }

                        Shizuku.removeBinderReceivedListener(this)

                        countDownLatch.countDown()
                    }
                }

                Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

                return try {
                    countDownLatch.await(5, TimeUnit.SECONDS)
                    reply
                } catch (e: TimeoutException) {
                    LOGGER.e(e, "Binder not received in 5s")
                    null
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }
}
