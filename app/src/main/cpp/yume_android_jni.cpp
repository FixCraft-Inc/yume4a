/*
 * YUME Android JNI adapter.
 *
 * It adapts handles, buffers and one callback between Kotlin and the public C
 * ABI of libyume (yume/yume.h). The protocol, the cryptography, the device
 * bridge and every decision about them live in libyume. Nothing here parses a
 * configuration, a packet or a key.
 *
 * Kotlin serializes use of one client: YumeClient holds a read-write lock, so
 * destroy never races another call on the same handle, as the ABI requires.
 */

#include <jni.h>

#include <yume/yume.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>
#include <vector>

namespace {

constexpr std::size_t kMaxConfigBytes = 1024U * 1024U;
// The largest sealed kit (docs/protocol/SEALED_KIT_1.md) and a typed code
// with separators and spaces.
constexpr std::size_t kMaxSealedBytes = 16U + 12U + 4U + 1024U * 1024U + 1024U + 16U;
constexpr std::size_t kMaxCodeBytes = 128U;
constexpr std::size_t kMaxTextBytes = 4096U;

enum StatusIndex : jsize {
    kStatusState = 0,
    kStatusSession = 1,
    kStatusLastFailure = 2,
    kStatusEpochBytes = 3,
    kStatusConnectedMs = 4,
    kStatusRetryMs = 5,
    kStatusSessions = 6,
    kStatusFailedAttempts = 7,
    kStatusIdleEpochRotation = 8,
    kStatusPayloadSent = 9,
    kStatusPayloadReceived = 10,
    kStatusRecordSent = 11,
    kStatusRecordReceived = 12,
    kStatusMaxQueued = 13,
    kStatusMaxEpoch = 14,
    kStatusCreditReturns = 15,
    kStatusTcpConnections = 16,
    kStatusUdpDestinations = 17,
    // YumeNative.STATUS_NUMBERS mirrors this count; the indexes above are
    // YumeNative.STATUS_* in the same order.
    kStatusCount = 18,
};

JavaVM* g_vm = nullptr;
jclass g_string_class = nullptr;
jmethodID g_protect = nullptr;
jmethodID g_sink_add = nullptr;

struct Client final {
    yume_runtime* runtime{nullptr};
    yume_endpoint* endpoint{nullptr};
    // Global reference to the SocketProtector, or null.
    jobject protector{nullptr};
    // Releases the library handles only. destroy() releases the protector's
    // global reference after this, once no runner thread can call it.
    ~Client() noexcept {
        yume_endpoint_destroy(endpoint);
        yume_runtime_destroy(runtime);
    }
};

struct RuntimeDeleter {
    void operator()(yume_runtime* value) const noexcept { yume_runtime_destroy(value); }
};
struct ConfigDeleter {
    void operator()(yume_config* value) const noexcept { yume_config_destroy(value); }
};
struct KitDeleter {
    void operator()(yume_kit* value) const noexcept { yume_kit_destroy(value); }
};
using RuntimePtr = std::unique_ptr<yume_runtime, RuntimeDeleter>;
using ConfigPtr = std::unique_ptr<yume_config, ConfigDeleter>;
using KitPtr = std::unique_ptr<yume_kit, KitDeleter>;

// Diagnostics and peer-reported text are bytes YUME bounds but does not
// promise to be valid modified UTF-8, which NewStringUTF requires. Anything
// outside printable ASCII becomes a question mark.
jstring text(JNIEnv* env, const char* value) {
    std::string safe(value != nullptr ? value : "");
    for (char& byte : safe) {
        const auto code = static_cast<unsigned char>(byte);
        if (code < 0x20U || code > 0x7eU) byte = '?';
    }
    return env->NewStringUTF(safe.c_str());
}

void put(JNIEnv* env, jobjectArray array, jsize index, const char* value) {
    if (array == nullptr || env->GetArrayLength(array) <= index) return;
    jstring item = text(env, value);
    if (item == nullptr) return;
    env->SetObjectArrayElement(array, index, item);
    env->DeleteLocalRef(item);
}

// Copies a handle's diagnostic into result: its JSON pointer, then its text.
void report(JNIEnv* env, jobjectArray result, const void* handle) {
    yume_diagnostic diagnostic{};
    diagnostic.struct_size = sizeof(diagnostic);
    diagnostic.abi_version = YUME_ABI_VERSION;
    if (handle == nullptr ||
        yume_handle_get_diagnostic(handle, &diagnostic, sizeof(diagnostic)) !=
            YUME_STATUS_OK) {
        put(env, result, 0, "");
        put(env, result, 1, "");
        return;
    }
    put(env, result, 0, diagnostic.json_pointer);
    put(env, result, 1, diagnostic.message);
}

void wipe(std::vector<std::uint8_t>& bytes) noexcept {
    volatile std::uint8_t* cursor = bytes.data();
    for (std::size_t index = 0; index < bytes.size(); ++index) cursor[index] = 0;
    bytes.clear();
}

// Copies a Java byte array of at most limit bytes. False when it is null,
// empty or longer.
bool copy_bytes(JNIEnv* env, jbyteArray array, std::size_t limit,
                std::vector<std::uint8_t>& out) {
    out.clear();
    if (array == nullptr) return false;
    const jsize size = env->GetArrayLength(array);
    if (size <= 0 || static_cast<std::size_t>(size) > limit) return false;
    out.resize(static_cast<std::size_t>(size));
    env->GetByteArrayRegion(array, 0, size,
                            reinterpret_cast<jbyte*>(out.data()));
    return !env->ExceptionCheck();
}

bool copy_string(JNIEnv* env, jstring value, std::string& out) {
    out.clear();
    if (value == nullptr) return true;
    const jsize size = env->GetStringUTFLength(value);
    if (size < 0 || static_cast<std::size_t>(size) > kMaxTextBytes) return false;
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    out.assign(chars, static_cast<std::size_t>(size));
    env->ReleaseStringUTFChars(value, chars);
    return true;
}

yume_string_view view(const std::string& value) {
    return yume_string_view{value.data(), value.size()};
}

yume_runtime* make_runtime(const char* base_dir) {
    yume_runtime_options options{};
    options.struct_size = sizeof(options);
    options.abi_version = YUME_ABI_VERSION;
    // No resolver program: the application resolves the server on the
    // underlying network and names the address in the configuration.
    options.config_base_dir = base_dir;
    yume_runtime* runtime = nullptr;
    return yume_runtime_create(&options, &runtime) == YUME_STATUS_OK ? runtime
                                                                     : nullptr;
}

// Runs on libyume's runner thread after an outbound socket is created and
// before it connects. Returning zero fails the connection closed. No YUME
// function may be entered from here.
int protect_socket(uintptr_t socket_handle, void* user_data) {
    auto* client = static_cast<Client*>(user_data);
    if (client == nullptr || client->protector == nullptr || g_vm == nullptr ||
        socket_handle > static_cast<uintptr_t>(std::numeric_limits<jint>::max())) {
        return 0;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    const jint state =
        g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        // The NDK declares this parameter as JNIEnv**. Only GetEnv takes void**.
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    } else if (state != JNI_OK || env == nullptr) {
        return 0;
    }
    const jboolean allowed = env->CallBooleanMethod(
        client->protector, g_protect, static_cast<jint>(socket_handle));
    bool ok = allowed == JNI_TRUE;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = false;
    }
    if (attached) g_vm->DetachCurrentThread();
    return ok ? 1 : 0;
}

void destroy(JNIEnv* env, Client* client) {
    if (client == nullptr) return;
    // Destroying the endpoint stops it and joins its threads, so the
    // protector cannot be called after its reference is released.
    yume_endpoint_destroy(client->endpoint);
    client->endpoint = nullptr;
    const jobject protector = client->protector;
    client->protector = nullptr;
    delete client;
    if (protector != nullptr) env->DeleteGlobalRef(protector);
}

jobjectArray native_compatibility(JNIEnv* env, jclass) try {
    yume_compatibility info{};
    info.struct_size = sizeof(info);
    info.abi_version = YUME_ABI_VERSION;
    if (yume_get_compatibility(&info, sizeof(info)) != YUME_STATUS_OK) {
        return nullptr;
    }
    const std::string numbers[] = {
        std::to_string(info.abi_version),
        std::to_string(info.ytp_version),
        std::to_string(info.config_schema),
        std::to_string(info.evidence_profile_version),
    };
    const char* values[] = {
        info.product_version,
        info.ytp_name,
        info.suite,
        info.crypto_backend,
        info.secure_channel_provider,
        info.front_door_provider,
        info.carrier_provider,
        info.session_component,
        info.session_security_provider,
        info.evidence_profile,
        numbers[0].c_str(),
        numbers[1].c_str(),
        numbers[2].c_str(),
        numbers[3].c_str(),
    };
    constexpr jsize kCount = sizeof(values) / sizeof(values[0]);
    jobjectArray result = env->NewObjectArray(kCount, g_string_class, nullptr);
    if (result == nullptr) return nullptr;
    for (jsize index = 0; index < kCount; ++index) {
        put(env, result, index, values[index]);
    }
    return result;
} catch (const std::bad_alloc&) {
    return nullptr;
} catch (...) {
    return nullptr;
}

jstring native_status_name(JNIEnv* env, jclass, jint code) try {
    yume_status_info info{};
    info.struct_size = sizeof(info);
    info.abi_version = YUME_ABI_VERSION;
    if (yume_get_status_info(static_cast<yume_status>(code), &info,
                             sizeof(info)) != YUME_STATUS_OK) {
        return text(env, "unknown");
    }
    return text(env, info.name);
} catch (...) {
    // text() allocates too, so a failure here returns no name at all.
    return nullptr;
}

// Checks a schema-1 document as libyume's strict parser does. role[0]
// receives YUME_ROLE_CLIENT or YUME_ROLE_SERVER on success.
jint native_check_config(JNIEnv* env, jclass, jbyteArray json, jintArray role,
                         jobjectArray result) try {
    std::vector<std::uint8_t> bytes;
    if (!copy_bytes(env, json, kMaxConfigBytes, bytes)) {
        put(env, result, 1, "the configuration is empty or larger than 1 MiB");
        return YUME_STATUS_INVALID_ARGUMENT;
    }
    RuntimePtr runtime(make_runtime(nullptr));
    if (!runtime) return YUME_STATUS_INTERNAL_ERROR;
    yume_config* raw_config = nullptr;
    const yume_status status =
        yume_config_parse_json(runtime.get(), bytes.data(), bytes.size(), &raw_config);
    ConfigPtr config(raw_config);
    if (status == YUME_STATUS_OK) {
        const jint parsed = static_cast<jint>(yume_config_role(config.get()));
        if (role != nullptr && env->GetArrayLength(role) > 0) {
            env->SetIntArrayRegion(role, 0, 1, &parsed);
        }
    } else {
        report(env, result, runtime.get());
    }
    return status;
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

// Opens a sealed kit and hands each file to sink. The code is wiped here.
// The file bytes become Java arrays that the caller wipes after storing them.
jint native_open_kit(JNIEnv* env, jclass, jbyteArray sealed, jbyteArray code,
                     jobject sink, jobjectArray result) try {
    std::vector<std::uint8_t> sealed_bytes;
    std::vector<std::uint8_t> code_bytes;
    if (sink == nullptr ||
        !copy_bytes(env, sealed, kMaxSealedBytes, sealed_bytes) ||
        !copy_bytes(env, code, kMaxCodeBytes, code_bytes)) {
        wipe(code_bytes);
        put(env, result, 1, "a sealed kit and its code are required");
        return YUME_STATUS_INVALID_ARGUMENT;
    }
    RuntimePtr runtime(make_runtime(nullptr));
    if (runtime == nullptr) {
        wipe(code_bytes);
        return YUME_STATUS_INTERNAL_ERROR;
    }
    yume_kit* raw_kit = nullptr;
    yume_status status = yume_kit_open(
        runtime.get(), sealed_bytes.data(), sealed_bytes.size(),
        reinterpret_cast<const char*>(code_bytes.data()), code_bytes.size(),
        &raw_kit);
    KitPtr kit(raw_kit);
    wipe(code_bytes);
    if (status != YUME_STATUS_OK) {
        report(env, result, runtime.get());
        return status;
    }
    const std::size_t count = yume_kit_file_count(kit.get());
    for (std::size_t index = 0; index < count && status == YUME_STATUS_OK;
         ++index) {
        yume_kit_file file{};
        file.struct_size = sizeof(file);
        file.abi_version = YUME_ABI_VERSION;
        status = yume_kit_get_file(kit.get(), index, &file, sizeof(file));
        if (status != YUME_STATUS_OK) break;
        const std::string path(file.path.data, file.path.size);
        jstring name = env->NewStringUTF(path.c_str());
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(file.size));
        if (name == nullptr || bytes == nullptr) {
            env->ExceptionClear();
            status = YUME_STATUS_RESOURCE_EXHAUSTED;
        } else {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(file.size),
                                    static_cast<const jbyte*>(file.data));
            env->CallVoidMethod(sink, g_sink_add, name,
                                file.executable != 0U ? JNI_TRUE : JNI_FALSE,
                                bytes);
            // A sink that throws ends the import. The exception stays pending
            // for the Kotlin caller.
            if (env->ExceptionCheck()) status = YUME_STATUS_INTERNAL_ERROR;
        }
        if (name != nullptr) env->DeleteLocalRef(name);
        if (bytes != nullptr) env->DeleteLocalRef(bytes);
    }
    return status;
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

// Creates a client endpoint from a schema-1 document whose credential
// references resolve against base_dir. status[0] receives the outcome.
jlong native_create(JNIEnv* env, jclass, jstring base_dir, jbyteArray json,
                    jobject protector, jintArray status_out,
                    jobjectArray result) try {
    jint outcome = YUME_STATUS_INVALID_ARGUMENT;
    const auto finish = [&](Client* client) -> jlong {
        if (status_out != nullptr && env->GetArrayLength(status_out) > 0) {
            env->SetIntArrayRegion(status_out, 0, 1, &outcome);
        }
        return reinterpret_cast<jlong>(client);
    };
    std::string base;
    std::vector<std::uint8_t> bytes;
    // A VPN endpoint without socket protection would route its own transport
    // into the VPN, so a missing protector fails closed.
    if (protector == nullptr || !copy_string(env, base_dir, base) || base.empty() ||
        !copy_bytes(env, json, kMaxConfigBytes, bytes)) {
        put(env, result, 1, "a base directory, a configuration and a socket protector are required");
        return finish(nullptr);
    }
    std::unique_ptr<Client> client(new (std::nothrow) Client());
    if (!client) {
        outcome = YUME_STATUS_RESOURCE_EXHAUSTED;
        return finish(nullptr);
    }
    client->runtime = make_runtime(base.c_str());
    if (client->runtime == nullptr) {
        outcome = YUME_STATUS_INTERNAL_ERROR;
        return finish(nullptr);
    }
    yume_config* raw_config = nullptr;
    outcome = yume_config_parse_json(client->runtime, bytes.data(), bytes.size(),
                                     &raw_config);
    ConfigPtr config(raw_config);
    if (outcome == YUME_STATUS_OK && yume_config_role(config.get()) != YUME_ROLE_CLIENT) {
        outcome = YUME_STATUS_INVALID_ARGUMENT;
        put(env, result, 0, "/role");
        put(env, result, 1, "the configuration is not a client's");
    } else if (outcome != YUME_STATUS_OK) {
        report(env, result, client->runtime);
    } else {
        outcome = yume_endpoint_create(client->runtime, config.get(), &client->endpoint);
        if (outcome != YUME_STATUS_OK) report(env, result, client->runtime);
    }
    if (outcome == YUME_STATUS_OK) {
        client->protector = env->NewGlobalRef(protector);
        if (client->protector == nullptr) {
            outcome = YUME_STATUS_RESOURCE_EXHAUSTED;
        } else {
            outcome = yume_endpoint_set_socket_protector(
                client->endpoint, &protect_socket, client.get());
            if (outcome != YUME_STATUS_OK) report(env, result, client->endpoint);
        }
    }
    if (outcome != YUME_STATUS_OK) {
        destroy(env, client.release());
        return finish(nullptr);
    }
    return finish(client.release());
} catch (const std::bad_alloc&) {
    if (status_out != nullptr && env->GetArrayLength(status_out) > 0) {
        const jint status = YUME_STATUS_RESOURCE_EXHAUSTED;
        env->SetIntArrayRegion(status_out, 0, 1, &status);
    }
    return 0L;
} catch (...) {
    if (status_out != nullptr && env->GetArrayLength(status_out) > 0) {
        const jint status = YUME_STATUS_INTERNAL_ERROR;
        env->SetIntArrayRegion(status_out, 0, 1, &status);
    }
    return 0L;
}

// Attaches the VPN's device, or detaches it when descriptor is negative.
jint native_set_device(JNIEnv* env, jclass, jlong handle, jint descriptor,
                       jint mtu, jstring stream_service, jstring packet_service,
                       jstring ipv4_address, jstring ipv4_peer,
                       jstring ipv6_address, jstring ipv6_peer,
                       jobjectArray result) try {
    auto* client = reinterpret_cast<Client*>(handle);
    if (client == nullptr) return YUME_STATUS_INVALID_ARGUMENT;
    yume_status status = YUME_STATUS_INVALID_ARGUMENT;
    if (descriptor < 0) {
        status = yume_endpoint_set_device(client->endpoint, nullptr);
    } else {
        std::string stream;
        std::string packet;
        std::string address4;
        std::string peer4;
        std::string address6;
        std::string peer6;
        if (mtu <= 0 || !copy_string(env, stream_service, stream) ||
            !copy_string(env, packet_service, packet) ||
            !copy_string(env, ipv4_address, address4) ||
            !copy_string(env, ipv4_peer, peer4) ||
            !copy_string(env, ipv6_address, address6) ||
            !copy_string(env, ipv6_peer, peer6)) {
            put(env, result, 1, "the device options are invalid");
            return YUME_STATUS_INVALID_ARGUMENT;
        }
        yume_device_options options{};
        options.struct_size = sizeof(options);
        options.abi_version = YUME_ABI_VERSION;
        options.descriptor = descriptor;
        options.mtu = static_cast<std::uint32_t>(mtu);
        options.stream_service = view(stream);
        options.packet_service = view(packet);
        options.ipv4_address = view(address4);
        options.ipv4_peer = view(peer4);
        options.ipv6_address = view(address6);
        options.ipv6_peer = view(peer6);
        status = yume_endpoint_set_device(client->endpoint, &options);
    }
    if (status != YUME_STATUS_OK) report(env, result, client->endpoint);
    return status;
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

// Blocks until the first session is authenticated, the attempt fails, the
// deadline passes or another thread stops the endpoint.
jint native_start(JNIEnv* env, jclass, jlong handle, jint timeout_ms,
                  jobjectArray result) try {
    auto* client = reinterpret_cast<Client*>(handle);
    if (client == nullptr || timeout_ms <= 0) return YUME_STATUS_INVALID_ARGUMENT;
    const yume_status status = yume_endpoint_start(
        client->endpoint, static_cast<std::uint32_t>(timeout_ms));
    if (status != YUME_STATUS_OK) report(env, result, client->endpoint);
    return status;
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

jint native_stop(JNIEnv*, jclass, jlong handle) try {
    auto* client = reinterpret_cast<Client*>(handle);
    if (client == nullptr) return YUME_STATUS_INVALID_ARGUMENT;
    return yume_endpoint_stop(client->endpoint, 0U);
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

jint native_retry_now(JNIEnv*, jclass, jlong handle) try {
    auto* client = reinterpret_cast<Client*>(handle);
    if (client == nullptr) return YUME_STATUS_INVALID_ARGUMENT;
    return yume_endpoint_retry_now(client->endpoint);
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

// numbers receives the snapshot's numeric fields in the order YumeClient
// reads them, and texts the server's fingerprint in hex and the latest
// failure's text.
jint native_status(JNIEnv* env, jclass, jlong handle, jlongArray numbers,
                   jobjectArray texts) try {
    auto* client = reinterpret_cast<Client*>(handle);
    if (client == nullptr || numbers == nullptr) return YUME_STATUS_INVALID_ARGUMENT;
    yume_endpoint_status status{};
    status.struct_size = sizeof(status);
    status.abi_version = YUME_ABI_VERSION;
    const yume_status outcome =
        yume_endpoint_get_status(client->endpoint, &status, sizeof(status));
    if (outcome != YUME_STATUS_OK) return outcome;
    jlong values[kStatusCount] = {};
    values[kStatusState] = static_cast<jlong>(status.state);
    values[kStatusSession] = static_cast<jlong>(status.session);
    values[kStatusLastFailure] = static_cast<jlong>(status.last_failure);
    values[kStatusEpochBytes] = static_cast<jlong>(status.epoch_bytes);
    values[kStatusConnectedMs] = static_cast<jlong>(status.connected_ms);
    values[kStatusRetryMs] = static_cast<jlong>(status.retry_ms);
    values[kStatusSessions] = static_cast<jlong>(status.sessions);
    values[kStatusFailedAttempts] = static_cast<jlong>(status.failed_attempts);
    values[kStatusIdleEpochRotation] = static_cast<jlong>(status.idle_epoch_rotation);
    values[kStatusPayloadSent] = static_cast<jlong>(status.payload_bytes_sent);
    values[kStatusPayloadReceived] = static_cast<jlong>(status.payload_bytes_received);
    values[kStatusRecordSent] = static_cast<jlong>(status.record_bytes_sent);
    values[kStatusRecordReceived] = static_cast<jlong>(status.record_bytes_received);
    values[kStatusMaxQueued] = static_cast<jlong>(status.max_queued_bytes);
    values[kStatusMaxEpoch] = static_cast<jlong>(status.max_epoch_bytes);
    values[kStatusCreditReturns] = static_cast<jlong>(status.credit_returns_per_window);
    values[kStatusTcpConnections] = static_cast<jlong>(status.device_tcp_connections);
    values[kStatusUdpDestinations] = static_cast<jlong>(status.device_udp_destinations);
    constexpr jsize kCount = kStatusCount;
    if (env->GetArrayLength(numbers) < kCount) return YUME_STATUS_BUFFER_TOO_SMALL;
    env->SetLongArrayRegion(numbers, 0, kCount, values);
    static constexpr char kHex[] = "0123456789abcdef";
    char fingerprint[65] = {};
    bool present = false;
    for (std::size_t index = 0; index < 32U; ++index) {
        const std::uint8_t byte = status.peer_fingerprint_sha256[index];
        present = present || byte != 0U;
        fingerprint[index * 2U] = kHex[byte >> 4U];
        fingerprint[index * 2U + 1U] = kHex[byte & 0x0fU];
    }
    put(env, texts, 0, present ? fingerprint : "");
    put(env, texts, 1, status.last_failure_message);
    return YUME_STATUS_OK;
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

// numbers receives the line's sequence number, the count of lines missed
// before it and its time. texts receives the feed's instance and the line.
jint native_read_message(JNIEnv* env, jclass, jlong handle, jlong after,
                         jlongArray numbers, jobjectArray texts) try {
    auto* client = reinterpret_cast<Client*>(handle);
    if (client == nullptr || numbers == nullptr || after < 0) {
        return YUME_STATUS_INVALID_ARGUMENT;
    }
    yume_message message{};
    message.struct_size = sizeof(message);
    message.abi_version = YUME_ABI_VERSION;
    const yume_status outcome = yume_endpoint_read_message(
        client->endpoint, static_cast<std::uint64_t>(after), &message,
        sizeof(message));
    if (outcome != YUME_STATUS_OK && outcome != YUME_STATUS_WOULD_BLOCK) {
        return outcome;
    }
    const jlong values[] = {
        static_cast<jlong>(message.seq),
        static_cast<jlong>(message.missed),
        static_cast<jlong>(message.time_unix_ms),
    };
    if (env->GetArrayLength(numbers) < 3) return YUME_STATUS_BUFFER_TOO_SMALL;
    env->SetLongArrayRegion(numbers, 0, 3, values);
    put(env, texts, 0, message.instance);
    put(env, texts, 1, message.text);
    return outcome;
} catch (const std::bad_alloc&) {
    return YUME_STATUS_RESOURCE_EXHAUSTED;
} catch (...) {
    return YUME_STATUS_INTERNAL_ERROR;
}

void native_destroy(JNIEnv* env, jclass, jlong handle) try {
    destroy(env, reinterpret_cast<Client*>(handle));
} catch (...) {
    // Destruction is noexcept by contract; a third-party destructor must not
    // unwind through JNI. The handle has already been detached by Kotlin.
}

const JNINativeMethod kMethods[] = {
    {"nativeCompatibility", "()[Ljava/lang/String;",
     reinterpret_cast<void*>(native_compatibility)},
    {"nativeStatusName", "(I)Ljava/lang/String;",
     reinterpret_cast<void*>(native_status_name)},
    {"nativeCheckConfig", "([B[I[Ljava/lang/String;)I",
     reinterpret_cast<void*>(native_check_config)},
    {"nativeOpenKit",
     "([B[BLcom/fixcraft/yume/core/KitSink;[Ljava/lang/String;)I",
     reinterpret_cast<void*>(native_open_kit)},
    {"nativeCreate",
     "(Ljava/lang/String;[BLcom/fixcraft/yume/core/SocketProtector;[I"
     "[Ljava/lang/String;)J",
     reinterpret_cast<void*>(native_create)},
    {"nativeSetDevice",
     "(JIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
     "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
     "[Ljava/lang/String;)I",
     reinterpret_cast<void*>(native_set_device)},
    {"nativeStart", "(JI[Ljava/lang/String;)I",
     reinterpret_cast<void*>(native_start)},
    {"nativeStop", "(J)I", reinterpret_cast<void*>(native_stop)},
    {"nativeRetryNow", "(J)I", reinterpret_cast<void*>(native_retry_now)},
    {"nativeStatus", "(J[J[Ljava/lang/String;)I",
     reinterpret_cast<void*>(native_status)},
    {"nativeReadMessage", "(JJ[J[Ljava/lang/String;)I",
     reinterpret_cast<void*>(native_read_message)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(native_destroy)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return JNI_ERR;
    }
    jclass native = env->FindClass("com/fixcraft/yume/core/YumeNative");
    jclass protector = env->FindClass("com/fixcraft/yume/core/SocketProtector");
    jclass sink = env->FindClass("com/fixcraft/yume/core/KitSink");
    jclass string = env->FindClass("java/lang/String");
    if (native == nullptr || protector == nullptr || sink == nullptr ||
        string == nullptr) {
        return JNI_ERR;
    }
    g_protect = env->GetMethodID(protector, "protect", "(I)Z");
    g_sink_add = env->GetMethodID(sink, "add", "(Ljava/lang/String;Z[B)V");
    g_string_class = static_cast<jclass>(env->NewGlobalRef(string));
    if (g_protect == nullptr || g_sink_add == nullptr ||
        g_string_class == nullptr ||
        env->RegisterNatives(native, kMethods,
                             sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    g_vm = vm;
    return JNI_VERSION_1_6;
}
