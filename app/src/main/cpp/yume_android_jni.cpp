/*
 * YUME Android JNI ownership adapter.
 * Protocol, crypto, framing, ratchets, and packet sequences live in libyume.
 */

#include <jni.h>

#include <yume/yume.h>

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr std::size_t kMaxJsonBytes = 1024 * 1024;
constexpr std::size_t kMaxBatchPackets = 64;

JavaVM* g_vm = nullptr;

struct Session {
    explicit Session(jobject protector_in) : protector(protector_in) {}

    std::mutex lifecycle_mu;
    // The packet ABI supports one reader and one writer concurrently. Keep
    // those directions independent so a bounded blocking read cannot stall
    // outbound TUN traffic. Close/destroy acquire both before freeing packet.
    std::mutex packet_read_mu;
    std::mutex packet_write_mu;
    std::mutex protector_mu;
    std::mutex error_mu;
    yume_client* client{nullptr};
    yume_packet* packet{nullptr};
    jobject protector{nullptr};
    std::string last_error;
    std::atomic<bool> closed{false};
};

std::mutex g_sessions_mu;
std::unordered_map<jlong, std::shared_ptr<Session>> g_sessions;
std::atomic<jlong> g_next_session{1};

std::shared_ptr<Session> find_session(jlong handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mu);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

void remember_error(Session& session, const void* handle, int status) {
    std::lock_guard<std::mutex> lock(session.error_mu);
    if (status == YUME_STATUS_OK) {
        session.last_error.clear();
        return;
    }
    const char* detail = handle ? yume_handle_last_error(handle) : nullptr;
    session.last_error = detail && *detail ? detail : yume_strerror(status);
}

jstring utf8(JNIEnv* env, const char* value) {
    return env->NewStringUTF(value ? value : "");
}

std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

int socket_protector(std::intptr_t socket_handle, void* user_data) {
    auto* session = static_cast<Session*>(user_data);
    if (!session || session->closed.load(std::memory_order_acquire) ||
        socket_handle < 0 ||
        socket_handle > std::numeric_limits<jint>::max()) {
        return 0;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    const jint env_status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env_status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    } else if (env_status != JNI_OK || !env) {
        return 0;
    }

    bool allowed = false;
    jobject protector = nullptr;
    {
        std::lock_guard<std::mutex> lock(session->protector_mu);
        protector = env->NewLocalRef(session->protector);
    }
    if (protector) {
        jclass cls = env->GetObjectClass(protector);
        jmethodID method = cls
            ? env->GetMethodID(cls, "protectSocket", "(I)Z")
            : nullptr;
        if (method) {
            allowed = env->CallBooleanMethod(
                protector, method, static_cast<jint>(socket_handle)) == JNI_TRUE;
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            allowed = false;
        }
        if (cls) env->DeleteLocalRef(cls);
        env->DeleteLocalRef(protector);
    }
    if (attached) g_vm->DetachCurrentThread();
    return allowed ? 1 : 0;
}

template <typename JsonCall>
jstring json_result(JNIEnv* env, Session& session, const void* handle, JsonCall&& call) {
    std::size_t needed = 0;
    int status = call(nullptr, 0, &needed);
    if (status != YUME_STATUS_BUFFER_TOO_SMALL || needed == 0 || needed > kMaxJsonBytes) {
        remember_error(session, handle, status);
        return nullptr;
    }
    std::vector<char> storage(needed);
    status = call(storage.data(), storage.size(), &needed);
    remember_error(session, handle, status);
    return status == YUME_STATUS_OK ? utf8(env, storage.data()) : nullptr;
}

void cleanup(JNIEnv* env, const std::shared_ptr<Session>& session) {
    if (!session || session->closed.exchange(true, std::memory_order_acq_rel)) return;
    {
        std::scoped_lock io_lock(session->packet_read_mu, session->packet_write_mu);
        std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
        if (session->packet) {
            (void)yume_packet_close(session->packet);
            yume_packet_destroy(session->packet);
            session->packet = nullptr;
        }
        if (session->client) {
            (void)yume_client_stop(session->client);
            yume_client_destroy(session->client);
            session->client = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> lock(session->protector_mu);
        if (session->protector) {
            env->DeleteGlobalRef(session->protector);
            session->protector = nullptr;
        }
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeCreate(
    JNIEnv* env, jobject, jobject protector) {
    if (!protector) return 0;
    jobject global = env->NewGlobalRef(protector);
    if (!global) return 0;
    auto session = std::make_shared<Session>(global);
    session->client = yume_client_create();
    if (!session->client ||
        yume_client_set_socket_protector(
            session->client, socket_protector, session.get()) != YUME_STATUS_OK) {
        if (session->client) yume_client_destroy(session->client);
        env->DeleteGlobalRef(global);
        return 0;
    }
    const jlong handle = g_next_session.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_sessions_mu);
    g_sessions.emplace(handle, std::move(session));
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeDestroy(
    JNIEnv* env, jobject, jlong handle) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mu);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        session = std::move(it->second);
        g_sessions.erase(it);
    }
    cleanup(env, session);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeAbiVersion(JNIEnv*, jobject) {
    return static_cast<jint>(yume_abi_version());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeFeatureFlags(JNIEnv*, jobject) {
    return static_cast<jint>(yume_feature_flags());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeYumeVersion(JNIEnv* env, jobject) {
    return utf8(env, yume_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeBasefwxVersion(JNIEnv* env, jobject) {
    return utf8(env, yume_basefwx_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativePqBackend(JNIEnv* env, jobject) {
    return utf8(env, yume_pq_backend());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeStart(
    JNIEnv* env, jobject, jlong handle, jstring config_json,
    jstring base_dir, jint timeout_ms) {
    auto session = find_session(handle);
    if (!session || timeout_ms <= 0) return YUME_STATUS_INVALID_ARGUMENT;
    const std::string config = from_jstring(env, config_json);
    const std::string base = from_jstring(env, base_dir);
    std::lock_guard<std::mutex> lock(session->lifecycle_mu);
    if (!session->client || session->closed.load()) return YUME_STATUS_NOT_RUNNING;
    const int status = yume_client_start_json(
        session->client, config.c_str(), base.c_str(),
        static_cast<std::uint32_t>(timeout_ms));
    remember_error(*session, session->client, status);
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeStop(
    JNIEnv*, jobject, jlong handle) {
    auto session = find_session(handle);
    if (!session) return YUME_STATUS_OK;
    std::scoped_lock io_lock(session->packet_read_mu, session->packet_write_mu);
    std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
    if (session->packet) {
        (void)yume_packet_close(session->packet);
        yume_packet_destroy(session->packet);
        session->packet = nullptr;
    }
    if (!session->client) return YUME_STATUS_OK;
    const int status = yume_client_stop(session->client);
    remember_error(*session, session->client, status);
    return status;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeClientStatus(
    JNIEnv* env, jobject, jlong handle) {
    auto session = find_session(handle);
    if (!session) return nullptr;
    std::lock_guard<std::mutex> lock(session->lifecycle_mu);
    if (!session->client) return nullptr;
    return json_result(env, *session, session->client,
        [&](char* out, std::size_t size, std::size_t* needed) {
            return yume_client_status_json(session->client, out, size, needed);
        });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeOpenPacket(
    JNIEnv*, jobject, jlong handle, jint timeout_ms) {
    auto session = find_session(handle);
    if (!session || timeout_ms <= 0) return YUME_STATUS_INVALID_ARGUMENT;
    std::scoped_lock io_lock(session->packet_read_mu, session->packet_write_mu);
    std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
    if (!session->client || session->packet) return YUME_STATUS_ALREADY_RUNNING;
    yume_packet* packet = nullptr;
    const int status = yume_client_open_packet(
        session->client, static_cast<std::uint32_t>(timeout_ms), &packet);
    remember_error(*session, session->client, status);
    if (status == YUME_STATUS_OK) session->packet = packet;
    return status;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativePacketStatus(
    JNIEnv* env, jobject, jlong handle) {
    auto session = find_session(handle);
    if (!session) return nullptr;
    std::scoped_lock io_lock(session->packet_read_mu, session->packet_write_mu);
    std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
    if (!session->packet) return nullptr;
    return json_result(env, *session, session->packet,
        [&](char* out, std::size_t size, std::size_t* needed) {
            return yume_packet_status_json(session->packet, out, size, needed);
        });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeWritePacketBatch(
    JNIEnv* env, jobject, jlong handle, jobject storage,
    jintArray offsets_array, jintArray lengths_array, jint count, jint timeout_ms) {
    auto session = find_session(handle);
    if (!session || !storage || !offsets_array || !lengths_array ||
        count <= 0 || static_cast<std::size_t>(count) > kMaxBatchPackets || timeout_ms < 0) {
        return YUME_STATUS_INVALID_ARGUMENT;
    }
    auto* bytes = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(storage));
    const jlong capacity = env->GetDirectBufferCapacity(storage);
    if (!bytes || capacity < 0 || env->GetArrayLength(offsets_array) < count ||
        env->GetArrayLength(lengths_array) < count) {
        return YUME_STATUS_INVALID_ARGUMENT;
    }
    std::vector<jint> offsets(static_cast<std::size_t>(count));
    std::vector<jint> lengths(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(offsets_array, 0, count, offsets.data());
    env->GetIntArrayRegion(lengths_array, 0, count, lengths.data());
    if (env->ExceptionCheck()) return YUME_STATUS_INVALID_ARGUMENT;
    std::vector<const void*> packets(static_cast<std::size_t>(count));
    std::vector<std::size_t> packet_lengths(static_cast<std::size_t>(count));
    for (jint i = 0; i < count; ++i) {
        if (offsets[i] < 0 || lengths[i] <= 0 ||
            static_cast<jlong>(offsets[i]) + lengths[i] > capacity) {
            return YUME_STATUS_INVALID_ARGUMENT;
        }
        packets[static_cast<std::size_t>(i)] = bytes + offsets[i];
        packet_lengths[static_cast<std::size_t>(i)] =
            static_cast<std::size_t>(lengths[i]);
    }
    std::lock_guard<std::mutex> io_lock(session->packet_write_mu);
    yume_packet* packet = nullptr;
    {
        std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
        packet = session->packet;
    }
    if (!packet) return YUME_STATUS_NOT_RUNNING;
    const int status = yume_packet_write_batch(
        packet, packets.data(), packet_lengths.data(), packets.size(),
        static_cast<std::uint32_t>(timeout_ms));
    remember_error(*session, packet, status);
    return status;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeReadPacketBatch(
    JNIEnv* env, jobject, jlong handle, jobject storage,
    jintArray offsets_array, jintArray lengths_array, jint timeout_ms) {
    auto session = find_session(handle);
    if (!session || !storage || !offsets_array || !lengths_array || timeout_ms < 0) return nullptr;
    auto* bytes = env->GetDirectBufferAddress(storage);
    const jlong capacity = env->GetDirectBufferCapacity(storage);
    const jsize array_capacity = std::min(
        env->GetArrayLength(offsets_array), env->GetArrayLength(lengths_array));
    if (!bytes || capacity <= 0 || array_capacity <= 0 ||
        static_cast<std::size_t>(array_capacity) > kMaxBatchPackets) return nullptr;
    std::vector<std::size_t> offsets(static_cast<std::size_t>(array_capacity));
    std::vector<std::size_t> lengths(static_cast<std::size_t>(array_capacity));
    std::size_t count = 0;
    std::size_t required = 0;
    int status = YUME_STATUS_NOT_RUNNING;
    {
        std::lock_guard<std::mutex> io_lock(session->packet_read_mu);
        yume_packet* packet = nullptr;
        {
            std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
            packet = session->packet;
        }
        if (packet) {
            status = yume_packet_read_batch(
                packet, bytes, static_cast<std::size_t>(capacity),
                offsets.data(), lengths.data(), offsets.size(), &count, &required,
                static_cast<std::uint32_t>(timeout_ms));
            remember_error(*session, packet, status);
        }
    }
    if (status == YUME_STATUS_OK) {
        std::vector<jint> java_offsets(count);
        std::vector<jint> java_lengths(count);
        for (std::size_t i = 0; i < count; ++i) {
            if (offsets[i] > static_cast<std::size_t>(std::numeric_limits<jint>::max()) ||
                lengths[i] > static_cast<std::size_t>(std::numeric_limits<jint>::max())) return nullptr;
            java_offsets[i] = static_cast<jint>(offsets[i]);
            java_lengths[i] = static_cast<jint>(lengths[i]);
        }
        env->SetIntArrayRegion(offsets_array, 0, static_cast<jsize>(count), java_offsets.data());
        env->SetIntArrayRegion(lengths_array, 0, static_cast<jsize>(count), java_lengths.data());
    }
    const jlong result_values[] = {
        static_cast<jlong>(status), static_cast<jlong>(count), static_cast<jlong>(required)};
    jlongArray result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, result_values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeClosePacket(
    JNIEnv*, jobject, jlong handle) {
    auto session = find_session(handle);
    if (!session) return YUME_STATUS_OK;
    std::scoped_lock io_lock(session->packet_read_mu, session->packet_write_mu);
    std::lock_guard<std::mutex> lifecycle_lock(session->lifecycle_mu);
    if (!session->packet) return YUME_STATUS_OK;
    const int status = yume_packet_close(session->packet);
    remember_error(*session, session->packet, status);
    yume_packet_destroy(session->packet);
    session->packet = nullptr;
    return status;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_SharedYumeNative_nativeLastError(
    JNIEnv* env, jobject, jlong handle) {
    auto session = find_session(handle);
    if (!session) return utf8(env, "native YUME session is closed");
    std::lock_guard<std::mutex> lock(session->error_mu);
    return utf8(env, session->last_error.c_str());
}
