package com.fixcraft.yume.i18n

import android.content.Context
import com.fixcraft.yume.data.AppSettings

private data class LogPatternRule(
    val pattern: Regex,
    val transform: (MatchResult, AppStrings) -> String,
)

object VpnLogTranslator {
    private val exactRules: Map<String, (AppStrings) -> String> = mapOf(
        "Android needs VPN permission before Yume can create the interface." to { strings -> strings.logAndroidNeedsVpnPermission() },
        "Android VPN permission granted." to { strings -> strings.logAndroidVpnPermissionGranted() },
        "Android VPN permission was denied." to { strings -> strings.logAndroidVpnPermissionDenied() },
        "Requesting Android notification permission for VPN status updates." to { strings -> strings.logRequestingNotificationPermission() },
        "Opening the Android VPN permission dialog." to { strings -> strings.logOpeningVpnPermissionDialog() },
        "Android VPN permission is already granted." to { strings -> strings.logAndroidVpnPermissionAlreadyGranted() },
        "The selected operator CA is unavailable." to { strings -> strings.logSelectedAnonymCaUnavailable() },
        "This server does not have an auth key assigned." to { strings -> strings.logSelectedAuthKeyRequired() },
        "The selected auth key is unavailable." to { strings -> strings.logSelectedAuthKeyUnavailable() },
        "Local CA and auth key are ready. Starting the Android VPN service." to { strings -> strings.logCredentialsReady() },
        "Protected the control socket from the VPN path." to { strings -> strings.logProtectedControlSocket() },
        "Received Yume AUTH challenge." to { strings -> strings.logReceivedAuthChallenge() },
        "Authenticated to the Yume server." to { strings -> strings.logAuthenticatedToServer() },
        "Running an in-app routed Android traffic probe through Yume." to { strings -> strings.logRunningRoutedProbe() },
        "Handshake failed" to { strings -> strings.logHandshakeFailed() },
        "TLS handshake failed on this Android TLS provider" to { strings -> strings.logTlsProviderHandshakeFailed() },
        "No upstream network is available. Waiting for Wi-Fi or cellular." to { strings -> strings.logNoUpstreamNetwork() },
        "Upstream network lost. Waiting for Wi-Fi or cellular." to { strings -> strings.logUpstreamNetworkLost() },
    )

    private val patternRules: List<LogPatternRule> = listOf(
        LogPatternRule(Regex("""^Selected server: (.+) \((.+)\)\.$""")) { match, strings ->
            strings.logSelectedServer(match.groupValues[1], match.groupValues[2])
        },
        LogPatternRule(Regex("""^Starting Yume Android session for (.+) \((.+)\)\.$""")) { match, strings ->
            strings.logStartingAndroidSession(match.groupValues[1], match.groupValues[2])
        },
        LogPatternRule(Regex("""^Requested protection policy: (.+)\.$""")) { match, strings ->
            strings.logRequestedProtectionPolicy(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Loaded operator CA: (.+)\.$""")) { match, strings ->
            strings.logLoadedAnonymCa(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Loaded auth key: (.+)\.$""")) { match, strings ->
            strings.logLoadedAuthKey(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Opening authenticated Yume control path on (.+)\.$""")) { match, strings ->
            strings.logOpeningAuthenticatedControlPath(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Selected IPv4 control endpoint (.+) for (.+)\.$""")) { match, strings ->
            strings.logSelectedIpv4ControlEndpoint(match.groupValues[1], match.groupValues[2])
        },
        LogPatternRule(Regex("""^TLS handshake completed: (.+)\.$""")) { match, strings ->
            strings.logTlsHandshakeCompleted(match.groupValues[1])
        },
        LogPatternRule(Regex("""^TLS handshake failed: (.+)$""")) { match, strings ->
            strings.logTlsHandshakeFailed(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Running a Yume relay canary via (.+)\.$""")) { match, strings ->
            strings.logRunningRelayCanary(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Verified app relay traffic via Yume\. Exit IP: (.+)\.$""")) { match, strings ->
            strings.logVerifiedAppRelayTraffic(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Verified system traffic via Yume\. Exit IP: (.+)\.$""")) { match, strings ->
            strings.logVerifiedSystemTraffic(match.groupValues[1])
        },
        LogPatternRule(Regex("""^VpnService\.prepare\(\) failed: (.+)$""")) { match, strings ->
            strings.logVpnPrepareFailed(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Unhandled VPN coroutine failure: (.+)$""")) { match, strings ->
            strings.logUnhandledVpnCoroutineFailure(match.groupValues[1])
        },
        LogPatternRule(Regex("""^Failed to verify systemwide Yume routing: (.+)$""")) { match, strings ->
            strings.logFailedToVerifySystemRouting(match.groupValues[1])
        },
    )

    fun translate(message: String, strings: AppStrings): String {
        exactRules[message]?.let { rule ->
            return rule(strings)
        }
        patternRules.forEach { rule ->
            rule.pattern.matchEntire(message)?.let { match ->
                return rule.transform(match, strings)
            }
        }
        return message
    }
}

fun logStrings(context: Context, settings: AppSettings): AppStrings {
    return appStrings(context, settings.effectiveLogLanguage().resolve(settings.language))
}
