package com.fixcraft.yume.provider

import android.content.Context
import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.core.YumeException
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.data.KitConfig
import com.fixcraft.yume.data.KitConfigException
import org.json.JSONException
import org.json.JSONObject

/** yume4a's public-ABI and device-config adapter. No enrollment state lives here. */
internal class YumeProviderNative(
    private val identity: NativeIdentityVerifier,
) : ProviderKitValidator.Native {
    fun interface NativeIdentityVerifier {
        fun fingerprint(files: List<KitFile>, originalConfig: ByteArray): String
    }

    override fun openKit(sealed: ByteArray, code: ByteArray): List<ProviderFile> = native {
        YumeLibrary.openKit(sealed, code).map { ProviderFile(it.path, it.executable, it.bytes) }
    }

    override fun checkClientConfig(config: ByteArray) = native {
        if (YumeLibrary.checkConfig(config) != YumeLibrary.ROLE_CLIENT) throw ProviderCodec.Refusal("unsupported")
    }

    override fun checkConsumerConfig(config: ByteArray) {
        try {
            checkProviderServicePolicy(JSONObject(config.toString(Charsets.UTF_8)))
            val description = KitConfig.describe(config)
            if (description.usesCircuits || description.usesProxy || description.connectAddress != null)
                throw ProviderCodec.Refusal("unsupported")
            val adapted = KitConfig.forDevice(config, null)
            try { checkClientConfig(adapted) } finally { adapted.fill(0) }
        } catch (_: KitConfigException) { throw ProviderCodec.Refusal("unsupported") }
        catch (_: JSONException) { throw ProviderCodec.Refusal("malformed") }
    }

    override fun fingerprint(files: List<ProviderFile>, originalConfig: ByteArray): String =
        identity.fingerprint(files.map { KitFile(it.path, it.executable, it.bytes) }, originalConfig)

    private fun <T> native(operation: () -> T): T = try { operation() }
    catch (_: YumeException) { throw ProviderCodec.Refusal("malformed") }

    companion object {
        /** yume4a's device bridge uses only these two authenticated native services. */
        internal fun checkProviderServicePolicy(document: JSONObject) {
            val services = document.getJSONArray("services")
            val required = mapOf("tcp" to "stream", "udp" to "packet")
            if (services.length() != required.size) throw ProviderCodec.Refusal("unsupported")
            val declared = (0 until services.length()).associate { position ->
                val service = services.getJSONObject(position)
                service.getString("name") to service.getString("kind")
            }
            if (declared != required) throw ProviderCodec.Refusal("unsupported")
            val adapters = document.getJSONArray("adapters")
            if (adapters.length() > 1) throw ProviderCodec.Refusal("unsupported")
            if (adapters.length() == 1) {
                val adapter = adapters.getJSONObject(0)
                if (adapter.getString("kind") != "socks5" || adapter.getString("service") != "tcp" ||
                    (adapter.has("udp_service") && adapter.getString("udp_service") != "udp")) {
                    throw ProviderCodec.Refusal("unsupported")
                }
            }
        }

        fun validator(context: Context, identity: NativeIdentityVerifier = ProviderNativeIdentityVerifier(context)) =
            ProviderKitValidator(YumeProviderNative(identity))
    }
}

/** Maps the app's JNI file value without copying or changing plaintext ownership. */
internal fun ProviderKitValidator.validateOpened(
    files: List<KitFile>, expectedIdentity: String, server: ProviderCodec.Server,
): ByteArray = validateOpened(files.map { ProviderFile(it.path, it.executable, it.bytes) }, expectedIdentity, server)
