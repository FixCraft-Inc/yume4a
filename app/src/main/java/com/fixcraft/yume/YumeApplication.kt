package com.fixcraft.yume

import android.app.Application
import android.content.Context
import com.fixcraft.yume.data.LegacyData
import com.fixcraft.yume.i18n.AppLocaleManager

class YumeApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        LegacyData.remove(this)
    }
}
