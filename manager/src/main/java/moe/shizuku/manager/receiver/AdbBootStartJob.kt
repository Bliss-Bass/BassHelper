package moe.shizuku.manager.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.WirelessAdb
import moe.shizuku.manager.starter.Starter
import rikka.shizuku.Shizuku

/**
 * Starts the server over wireless debugging after boot. Boot broadcasts usually arrive before
 * Wi-Fi connects and before adbd advertises its port, so this waits for a network and retries
 * with backoff instead of giving up after one short mDNS wait.
 *
 * When those attempts run out (no Wi-Fi, or a network where wireless debugging is refused), a
 * periodic job tries once more on each Wi-Fi network other than the one it failed on, so a laptop
 * booted away from Wi-Fi starts the server when it gets back. A PendingIntent network callback
 * cannot do this: the system drops it after one delivery, which comes at once for the current
 * network.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AdbBootStartJob : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = ShizukuSettings.getPreferences()
        if (Shizuku.pingBinder()) {
            prefs.edit().remove(KEY_ATTEMPTS).apply()
            if (params.jobId == RETRY_JOB_ID) cancelRetry(this)
            return false
        }
        if (params.jobId == RETRY_JOB_ID) return retryOnNewNetwork(params)
        work = CoroutineScope(Dispatchers.IO).launch {
            val attempt = prefs.getInt(KEY_ATTEMPTS, 0) + 1
            val started = startOverAdb()
            when {
                started -> {
                    Log.i(AppConstants.TAG, "Started on boot over wireless debugging (attempt $attempt)")
                    prefs.edit().remove(KEY_ATTEMPTS).apply()
                    onStarted()
                    jobFinished(params, false)
                }
                attempt >= MAX_ATTEMPTS -> {
                    Log.w(AppConstants.TAG, "Start on boot failed after $attempt attempts, retrying on the next Wi-Fi network")
                    prefs.edit().remove(KEY_ATTEMPTS).apply()
                    notifyFailed()
                    scheduleRetry(this@AdbBootStartJob, params.network)
                    jobFinished(params, false)
                }
                else -> {
                    Log.w(AppConstants.TAG, "Start on boot attempt $attempt failed, retrying")
                    prefs.edit().putInt(KEY_ATTEMPTS, attempt).apply()
                    jobFinished(params, true)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private fun retryOnNewNetwork(params: JobParameters): Boolean {
        val prefs = ShizukuSettings.getPreferences()
        val network = params.network ?: return false
        if (network.networkHandle == prefs.getLong(KEY_FAILED_NETWORK, 0L)) return false
        work = CoroutineScope(Dispatchers.IO).launch {
            Log.i(AppConstants.TAG, "Joined another Wi-Fi network, retrying start over wireless debugging")
            if (startOverAdb()) {
                Log.i(AppConstants.TAG, "Started over wireless debugging on retry")
                onStarted()
            } else {
                prefs.edit().putLong(KEY_FAILED_NETWORK, network.networkHandle).apply()
            }
            jobFinished(params, false)
        }
        return true
    }

    private fun onStarted() {
        cancelRetry(this)
        getSystemService(NotificationManager::class.java).cancel(AppConstants.NOTIFICATION_ID_STATUS)
    }

    private suspend fun startOverAdb(): Boolean {
        try {
            WirelessAdb.enable(contentResolver)
        } catch (e: SecurityException) {
            Log.w(AppConstants.TAG, "WRITE_SECURE_SETTINGS not granted, cannot turn on wireless debugging")
            return false
        }
        val port = WirelessAdb.discoverPort(this, AdbMdns.TLS_CONNECT)
        if (port == null) {
            Log.w(AppConstants.TAG, "Wireless debugging port not advertised")
            return false
        }
        return try {
            AdbClient("127.0.0.1", port, WirelessAdb.key()).use {
                it.connect()
                it.shellCommand(Starter.internalCommand, null)
            }
            true
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "Wireless debugging start failed", e)
            false
        }
    }

    private fun notifyFailed() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                AppConstants.NOTIFICATION_CHANNEL_STATUS,
                getString(R.string.notification_channel_service_status),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, AppConstants.NOTIFICATION_CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_start_on_boot_failed))
            .setContentText(getString(R.string.notification_start_on_boot_failed_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.notification_start_on_boot_failed_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(AppConstants.NOTIFICATION_ID_STATUS, notification)
    }

    companion object {
        private const val JOB_ID = 1001
        private const val RETRY_JOB_ID = 1002
        private const val RETRY_INTERVAL_MS = 15 * 60_000L
        private const val KEY_ATTEMPTS = "boot_start_attempts"
        private const val KEY_FAILED_NETWORK = "boot_start_failed_network"
        private const val MAX_ATTEMPTS = 8

        private fun scheduleRetry(context: Context, failedOn: Network?) {
            ShizukuSettings.getPreferences().edit()
                .putLong(KEY_FAILED_NETWORK, failedOn?.networkHandle ?: 0L)
                .apply()
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val job = JobInfo.Builder(RETRY_JOB_ID, ComponentName(context, AdbBootStartJob::class.java))
                .setRequiredNetwork(wifi)
                .setPeriodic(RETRY_INTERVAL_MS)
                .build()
            try {
                context.getSystemService(JobScheduler::class.java).schedule(job)
            } catch (e: Exception) {
                Log.w(AppConstants.TAG, "Wi-Fi retry job not scheduled", e)
            }
        }

        private fun cancelRetry(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(RETRY_JOB_ID)
            ShizukuSettings.getPreferences().edit().remove(KEY_FAILED_NETWORK).apply()
        }

        fun schedule(context: Context) {
            cancelRetry(context)
            ShizukuSettings.getPreferences().edit().remove(KEY_ATTEMPTS).apply()
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AdbBootStartJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()
            try {
                context.getSystemService(JobScheduler::class.java).schedule(job)
            } catch (e: Exception) {
                Log.w(AppConstants.TAG, "Start on boot job not scheduled", e)
            }
        }
    }
}
