package ch.fhnw.pepper_realtime.manager

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import ch.fhnw.pepper_realtime.BuildConfig
import ch.fhnw.pepper_realtime.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemTimeRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val mutex = Mutex()

    fun needsRecovery(): Boolean =
        ClockRecovery.isSupported(BuildConfig.FLAVOR, Build.VERSION.SDK_INT) &&
            System.currentTimeMillis() < ClockRecovery.MIN_VALID_TIME_MS

    /** True means connection may proceed. Cancellation belongs to the connection attempt. */
    suspend fun awaitValidClock(onProgress: (ClockRecoveryStage) -> Unit): Boolean = withContext(ioDispatcher) {
        if (!ClockRecovery.isSupported(BuildConfig.FLAVOR, Build.VERSION.SDK_INT)) return@withContext true
        mutex.withLock {
            try {
                val environment = AndroidClockRecoveryEnvironment(context)
                if (environment.wallTimeMs() >= ClockRecovery.MIN_VALID_TIME_MS) {
                    Log.d(TAG, "Clock is plausible; no recovery needed")
                    return@withLock true
                }
                Log.w(TAG, "Reset clock detected; checking automatic time and waiting for network")
                val result = ClockRecovery(environment).recover(onProgress)
                if (result == ClockRecovery.Result.RECOVERED) {
                    Log.i(TAG, "Clock is plausible again; ending recovery")
                } else {
                    Log.w(TAG, "Clock recovery ended: $result")
                }
                result == ClockRecovery.Result.RECOVERED || result == ClockRecovery.Result.ALREADY_VALID
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Clock recovery unavailable on this firmware", e)
                false
            }
        }
    }

    companion object {
        internal const val TAG = "SystemTimeRecovery"
    }
}

internal class AndroidClockRecoveryEnvironment(private val context: Context) : ClockRecovery.Environment {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedTimeMs(): Long = SystemClock.elapsedRealtime()
    override fun automaticTimeEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1

    @Suppress("DEPRECATION")
    override fun isConnected(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .activeNetworkInfo?.isConnected == true

    override fun requestNetworkTime() {
        // Internal Android 6 system receiver, verified on Pepper. Not a public cross-version API.
        context.sendBroadcast(Intent("com.android.server.NetworkTimeUpdateService.action.POLL").setPackage("android"))
        Log.i(SystemTimeRecovery.TAG, "Requested system NTP poll; clock correction not yet confirmed")
    }
}
