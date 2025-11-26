package io.github.anonymous.pepper_realtime

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PepperApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

