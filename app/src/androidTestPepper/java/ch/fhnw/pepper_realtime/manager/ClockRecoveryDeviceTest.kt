package ch.fhnw.pepper_realtime.manager

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockRecoveryDeviceTest {
    @Test fun requestFromAppUidWithSimulatedResetDate() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 23)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val real = AndroidClockRecoveryEnvironment(context)
        assumeTrue(real.automaticTimeEnabled() && real.isConnected())
        assumeTrue(real.wallTimeMs() >= ClockRecovery.MIN_VALID_TIME_MS)
        var requests = 0
        val simulated = object : ClockRecovery.Environment by real {
            override fun wallTimeMs(): Long = if (requests == 0) 0L else real.wallTimeMs()
            override fun requestNetworkTime() {
                real.requestNetworkTime()
                requests++
            }
        }
        Log.i("SystemTimeRecovery", "Device test: simulated reset date, real poll from app UID; system clock unchanged by test")
        assertEquals(ClockRecovery.Result.RECOVERED, ClockRecovery(simulated).recover())
        assertEquals(1, requests)
        assertTrue(real.wallTimeMs() >= ClockRecovery.MIN_VALID_TIME_MS)
    }
}
