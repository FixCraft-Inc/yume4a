package com.fixcraft.yume

import android.app.Application
import android.content.Context
import com.fixcraft.yume.data.LegacyData
import com.fixcraft.yume.i18n.AppLocaleManager

class YumeApplication : Application() {
    private val serviceOwner = lazy {
        val registration = if (BuildConfig.PROVIDER_CLIENT_ID.isEmpty()) null else
            com.fixcraft.yume.provider.ProviderRegistration(packageName, BuildConfig.PROVIDER_CLIENT_ID, BuildConfig.PROVIDER_CALLBACK)
        com.fixcraft.yume.consumer.yumeServiceController(this, registration)
    }

    internal val services get() = serviceOwner.value

    // Explicit process-owner teardown for test/application hosts. Android process
    // termination itself releases OS resources and does not call onTerminate.
    internal fun closeServices() { if (serviceOwner.isInitialized()) serviceOwner.value.close() }

    override fun onTerminate() {
        closeServices()
        super.onTerminate()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        LegacyData.remove(this)
    }
}
