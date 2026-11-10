package io.github.jqssun.gpssetter

import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import timber.log.Timber

lateinit var gsApp: App

@HiltAndroidApp
class App : Application() {

    companion object {
        fun commonInit() {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gsApp = this
        commonInit()
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
        PrefManager.watch()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) = PrefManager.onServiceBind(service)
            override fun onServiceDied(service: XposedService) = PrefManager.onServiceDied()
        })
    }
}