package com.fixcraft.yume.agent

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.fixcraft.yume.BuildConfig

class AgentVpnConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.ENABLE_ADB_TEST_CONTROL) { "ADB test control is disabled" }
        AgentTelemetry.start(this)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            AgentTelemetry.writeConsent(this, true, "VPN consent is already granted")
            finish()
            return
        }
        AgentTelemetry.writeConsent(this, false, "Waiting for the Android VPN consent dialog")
        startActivityForResult(prepareIntent, REQUEST_VPN_CONSENT)
    }

    @Deprecated("The platform VPN consent API still returns through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_CONSENT) return
        val granted = resultCode == RESULT_OK && VpnService.prepare(this) == null
        AgentTelemetry.writeConsent(
            this,
            granted,
            if (granted) "VPN consent granted" else "VPN consent was not granted",
        )
        finish()
    }

    private companion object {
        const val REQUEST_VPN_CONSENT = 901
    }
}
