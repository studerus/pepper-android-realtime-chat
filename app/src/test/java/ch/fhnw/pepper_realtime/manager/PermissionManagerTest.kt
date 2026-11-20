package ch.fhnw.pepper_realtime.manager

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class PermissionManagerTest {
    private val manager = PermissionManager()
    private val code = PermissionManager.MEDIA_PERMISSION_REQUEST

    @Test fun `request only missing permissions in one call`() {
        val activity = mock<Activity>()
        mockStatic(ContextCompat::class.java).use { checks ->
            for (missing in listOf(listOf(RECORD_AUDIO, CAMERA), listOf(CAMERA), listOf(RECORD_AUDIO), emptyList())) {
                for (permission in listOf(RECORD_AUDIO, CAMERA)) {
                    checks.`when`<Int> { ContextCompat.checkSelfPermission(activity, permission) }
                        .thenReturn(if (permission in missing) PERMISSION_DENIED else PERMISSION_GRANTED)
                }
                mockStatic(ActivityCompat::class.java).use { requests ->
                    manager.checkAndRequestPermissions(activity)
                    if (missing.isNotEmpty()) requests.verify {
                        ActivityCompat.requestPermissions(activity, missing.toTypedArray(), code)
                    }
                    requests.verifyNoMoreInteractions()
                }
            }
        }
    }

    @Test fun `results are matched by permission and cancelled requests report no false denial`() {
        val callback = mock<PermissionManager.PermissionCallback>()
        manager.setCallback(callback)
        assertFalse(manager.handlePermissionResult(99, arrayOf(CAMERA), intArrayOf(PERMISSION_DENIED)))
        assertTrue(manager.handlePermissionResult(code, emptyArray(), intArrayOf()))
        verifyNoInteractions(callback)
        manager.handlePermissionResult(code, arrayOf(CAMERA, RECORD_AUDIO), intArrayOf(PERMISSION_DENIED, PERMISSION_GRANTED))
        verify(callback).onCameraDenied()
        verify(callback).onMicrophoneGranted()
        verifyNoMoreInteractions(callback)
    }
}
