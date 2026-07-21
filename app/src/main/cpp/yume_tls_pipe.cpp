// SPDX-License-Identifier: GPL-3.0-or-later
// YUME native TLS pipe — non-blocking OpenSSL I/O behind a JNI surface so the
// Kotlin frame codec stops blocking on SSLSocket.outputStream.write().
//
// Mirrors the desktop CLI's Tunnel design (boost::asio strand + async_write/
// async_read_some) without the boost dependency: one per-session worker
// thread, epoll for socket readiness, eventfd for wake-ups, and a producer/
// consumer split between the JNI thread (Kotlin frame codec) and the worker.
//
// Contract with the Kotlin side:
//   nativeStart  takes ownership of an already-connected TCP fd (protected
//                via VpnService.protect on the Kotlin side). The worker
//                thread drives the SSL handshake and then SSL_read/SSL_write
//                in a non-blocking loop. After nativeStart returns success,
//                handshake metadata (protocol/cipher/peer cert chain) is
//                available via the native getters.
//   nativeWrite  enqueues plaintext bytes onto a pending-tx queue and
//                signals the worker. Returns immediately. Never blocks the
//                JNI caller on socket I/O — that's the entire point.
//   nativeRead   blocks the JNI caller until plaintext bytes arrive (or
//                EOF / error / timeout). Same shape as InputStream.read.
//   nativeClose  graceful shutdown: send TLS close_notify, drain, exit.
//   nativeDestroy frees all state; must be called exactly once after
//                Close, not before.

#include <jni.h>

#include <openssl/bio.h>
#include <openssl/err.h>
#include <openssl/opensslv.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

#include <android/log.h>

#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "YumeNativeTls"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kIoBufSize = 64 * 1024;
constexpr size_t kMaxBufferedTx = 8 * 1024 * 1024;

struct TxChunk {
    std::vector<uint8_t> data;
    size_t offset{0};
};

// Drains an OpenSSL error queue into a string suitable for surfacing to Java.
std::string drain_ssl_errors() {
    std::string out;
    unsigned long err;
    while ((err = ERR_get_error()) != 0) {
        char buf[256];
        ERR_error_string_n(err, buf, sizeof(buf));
        if (!out.empty()) out += "; ";
        out += buf;
    }
    return out;
}

bool set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

class TlsPipe {
public:
    TlsPipe() {
        epoll_fd_ = epoll_create1(EPOLL_CLOEXEC);
        wake_fd_  = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    }

    ~TlsPipe() {
        // Worker should already be stopped via close() before destruction.
        stop_worker();
        if (ssl_)     { SSL_free(ssl_); ssl_ = nullptr; }
        if (ctx_)     { SSL_CTX_free(ctx_); ctx_ = nullptr; }
        if (epoll_fd_ >= 0) { ::close(epoll_fd_); epoll_fd_ = -1; }
        if (wake_fd_  >= 0) { ::close(wake_fd_);  wake_fd_  = -1; }
        if (fd_       >= 0) { ::close(fd_);       fd_       = -1; }
    }

    bool configure(const std::string& sni_host,
                   const std::string& trust_pem_concat,
                   bool use_system_trust,
                   const std::string& alpn_proto,
                   std::string* err) {
        sni_host_ = sni_host;
        alpn_proto_ = alpn_proto;

        ctx_ = SSL_CTX_new(TLS_client_method());
        if (!ctx_) {
            *err = "SSL_CTX_new failed: " + drain_ssl_errors();
            return false;
        }
        // TLS 1.3 only on the control path — matches Android Kotlin path.
        if (SSL_CTX_set_min_proto_version(ctx_, TLS1_3_VERSION) != 1 ||
            SSL_CTX_set_max_proto_version(ctx_, TLS1_3_VERSION) != 1) {
            *err = "Failed to pin TLS 1.3: " + drain_ssl_errors();
            return false;
        }
        SSL_CTX_set_verify(ctx_, SSL_VERIFY_PEER, nullptr);

        // SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER — without this, OpenSSL
        // returns SSL_R_BAD_WRITE_RETRY (error 0x0A00007F) any time we
        // retry SSL_write with a buffer at a different memory address.
        // Our tx queue copies frames into newly-allocated TxChunks, and
        // each WANT_WRITE retry pulls the front chunk into a fresh
        // local std::vector — different pointer every time → instant
        // session death the first time the kernel send buffer fills.
        // SSL_MODE_ENABLE_PARTIAL_WRITE lets SSL_write tell us it only
        // accepted part of the buffer instead of "all or nothing"; this
        // matches how non-blocking BIO actually behaves and lets us
        // advance the queue offset on partial sends. Both modes are
        // strictly opt-in and have to be set on the context BEFORE the
        // SSL object is created (or SSL_set_mode on the SSL itself).
        SSL_CTX_set_mode(ctx_,
            SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER |
            SSL_MODE_ENABLE_PARTIAL_WRITE);

        // Trust anchors. Append the user-supplied operator CA chain. The
        // historical Kotlin/ABI field name is retained for compatibility.
        // and optionally the system trust store. Without at least one of
        // these we can't validate the peer cert.
        if (!trust_pem_concat.empty()) {
            BIO* bio = BIO_new_mem_buf(trust_pem_concat.data(),
                                       static_cast<int>(trust_pem_concat.size()));
            if (!bio) {
                *err = "BIO_new_mem_buf failed";
                return false;
            }
            X509_STORE* store = SSL_CTX_get_cert_store(ctx_);
            int added = 0;
            while (true) {
                X509* cert = PEM_read_bio_X509(bio, nullptr, nullptr, nullptr);
                if (!cert) break;
                if (X509_STORE_add_cert(store, cert) == 1) {
                    ++added;
                }
                X509_free(cert);
            }
            BIO_free(bio);
            if (added == 0) {
                *err = "Trust PEM contained no usable certificates";
                return false;
            }
        }
        if (use_system_trust) {
            // Android system root store lives at /system/etc/security/cacerts.
            // OpenSSL can scan a directory of hashed certs; Android keeps a
            // legacy AOSP layout there for compat with NDK apps.
            if (SSL_CTX_load_verify_locations(ctx_, nullptr,
                    "/system/etc/security/cacerts") != 1) {
                // Non-fatal — the operator CA above is sufficient for the Yume
                // stealth path. Log and continue.
                LOGW("System trust store not loadable: %s",
                     drain_ssl_errors().c_str());
            }
        }
        return true;
    }

    int start(int fd, int handshake_timeout_ms, std::string* err) {
        // Contract: native takes ownership of `fd` even on error. The
        // destructor closes it (whether nativeStart succeeded or not),
        // so the JNI caller only has to call nativeDestroy on the
        // returned handle to clean up.
        if (fd < 0 || !ctx_ || epoll_fd_ < 0 || wake_fd_ < 0) {
            *err = "TlsPipe not properly initialised";
            if (fd >= 0) ::close(fd);
            return -1;
        }
        fd_ = fd;
        if (!set_nonblocking(fd_)) {
            *err = std::string("fcntl O_NONBLOCK failed: ") + strerror(errno);
            ::close(fd_);
            fd_ = -1;
            return -1;
        }
        // Match the CLI/server tunnel sockets: Yume already coalesces DATA
        // frames before TLS, so Nagle only adds latency to small control
        // frames and sparse uploads.
        int nodelay = 1;
        (void)setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY,
                         &nodelay, sizeof(nodelay));

        ssl_ = SSL_new(ctx_);
        if (!ssl_) {
            *err = "SSL_new failed: " + drain_ssl_errors();
            return -1;
        }
        if (!sni_host_.empty()) {
            SSL_set_tlsext_host_name(ssl_, sni_host_.c_str());
            // Hostname verification — replaces SSLSocket's
            // endpointIdentificationAlgorithm = "HTTPS" knob.
            X509_VERIFY_PARAM* vp = SSL_get0_param(ssl_);
            X509_VERIFY_PARAM_set_hostflags(vp,
                X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
            X509_VERIFY_PARAM_set1_host(vp, sni_host_.c_str(),
                                        sni_host_.size());
        }
        if (!alpn_proto_.empty()) {
            // ALPN list is len-prefixed concatenation; we only ever pass
            // a single protocol (e.g. "http/1.1") so build that on the fly.
            std::vector<uint8_t> wire;
            wire.reserve(1 + alpn_proto_.size());
            wire.push_back(static_cast<uint8_t>(alpn_proto_.size()));
            wire.insert(wire.end(), alpn_proto_.begin(), alpn_proto_.end());
            SSL_set_alpn_protos(ssl_, wire.data(),
                                static_cast<unsigned int>(wire.size()));
        }
        if (SSL_set_fd(ssl_, fd_) != 1) {
            *err = "SSL_set_fd failed: " + drain_ssl_errors();
            return -1;
        }
        SSL_set_connect_state(ssl_);

        // Register fd and wake_fd with epoll once; we'll just MOD the
        // event mask as needed.
        epoll_event ev{};
        ev.events = EPOLLIN;
        ev.data.fd = wake_fd_;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, wake_fd_, &ev) != 0) {
            *err = std::string("epoll_ctl wake_fd failed: ") + strerror(errno);
            return -1;
        }
        ev.events = EPOLLIN | EPOLLOUT;
        ev.data.fd = fd_;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fd_, &ev) != 0) {
            *err = std::string("epoll_ctl fd failed: ") + strerror(errno);
            return -1;
        }

        // Drive the handshake on the calling thread so nativeStart can
        // report success or failure synchronously. The worker thread
        // takes over after this.
        if (!run_handshake(handshake_timeout_ms, err)) {
            return -1;
        }
        capture_session_info();

        // Spawn the worker for the post-handshake I/O loop.
        running_.store(true);
        worker_ = std::thread([this] { run_io_loop(); });
        return 0;
    }

    // Enqueue plaintext bytes to be written by the worker. Never blocks
    // on socket I/O. Will BLOCK on the tx mutex when the queue would
    // exceed kMaxBufferedTx — that's the right thing to do under TCP
    // congestion: the producer (frame writer coroutine) needs to slow
    // down to match SSL_write drain rate, the same way SSLSocket's
    // blocking write does. Returns -1 only if the transport has been
    // closed or has failed; the caller may treat that as terminal.
    int write(const uint8_t* data, size_t len) {
        if (len == 0) return 0;
        {
            std::unique_lock<std::mutex> lk(tx_mu_);
            while (!tx_closed_ && !transport_failed_.load() &&
                   tx_buffered_ + len > kMaxBufferedTx) {
                // 500 ms wakeup is a defense-in-depth — we should also
                // be notified explicitly via tx_space_cv_ as bytes
                // drain on the worker thread, but if a notification is
                // ever missed (it shouldn't be) we retry periodically.
                tx_space_cv_.wait_for(lk, std::chrono::milliseconds(500));
            }
            if (tx_closed_ || transport_failed_.load()) {
                return -1;
            }
            TxChunk chunk;
            chunk.data.assign(data, data + len);
            tx_buffered_ += len;
            tx_queue_.emplace_back(std::move(chunk));
        }
        wake();
        return static_cast<int>(len);
    }

    // Block until at least one byte is available, EOF, error, or timeout.
    // Returns >0 bytes read, 0 on timeout (caller may retry), -1 on EOF,
    // -2 on error (errorMessage populated).
    int read(uint8_t* dst, size_t max_len, int timeout_ms,
             std::string* error_message) {
        if (max_len == 0) return 0;
        std::unique_lock<std::mutex> lk(rx_mu_);
        auto wait_until = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(timeout_ms < 0 ? 0 : timeout_ms);
        while (rx_queue_.empty() && !rx_eof_ && rx_error_.empty()) {
            if (timeout_ms < 0) {
                rx_cv_.wait(lk);
            } else if (timeout_ms == 0) {
                return 0;
            } else {
                if (rx_cv_.wait_until(lk, wait_until) ==
                        std::cv_status::timeout) {
                    return 0;
                }
            }
        }
        if (!rx_queue_.empty()) {
            auto& front = rx_queue_.front();
            size_t available = front.data.size() - front.offset;
            size_t take = std::min(available, max_len);
            std::memcpy(dst, front.data.data() + front.offset, take);
            front.offset += take;
            if (front.offset >= front.data.size()) {
                rx_queue_.pop_front();
            }
            return static_cast<int>(take);
        }
        if (!rx_error_.empty()) {
            if (error_message) *error_message = rx_error_;
            return -2;
        }
        // rx_eof_ is true
        return -1;
    }

    void close() {
        {
            std::lock_guard<std::mutex> lk(tx_mu_);
            tx_closed_ = true;
        }
        tx_space_cv_.notify_all();  // wake any producer blocked on backpressure
        wake();
        stop_worker();
    }

    const std::string& tls_protocol()    const { return tls_protocol_; }
    const std::string& tls_cipher_suite() const { return tls_cipher_suite_; }
    size_t peer_cert_count() const { return peer_cert_chain_.size(); }
    const std::vector<uint8_t>& peer_cert(size_t i) const {
        return peer_cert_chain_[i];
    }
    const std::string& handshake_error() const { return handshake_error_; }
    const std::string& alpn_selected() const { return alpn_selected_; }

private:
    void wake() {
        if (wake_fd_ < 0) return;
        uint64_t one = 1;
        ssize_t r = ::write(wake_fd_, &one, sizeof(one));
        (void)r;
    }

    void stop_worker() {
        bool expected = true;
        if (running_.compare_exchange_strong(expected, false)) {
            wake();
            if (worker_.joinable()) {
                worker_.join();
            }
        }
        // Make sure any blocked reader wakes up too.
        {
            std::lock_guard<std::mutex> lk(rx_mu_);
            if (!rx_eof_ && rx_error_.empty()) {
                rx_eof_ = true;
            }
        }
        rx_cv_.notify_all();
    }

    bool run_handshake(int timeout_ms, std::string* err) {
        auto deadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(timeout_ms);
        while (true) {
            int rc = SSL_connect(ssl_);
            if (rc == 1) {
                return true;
            }
            int ssl_err = SSL_get_error(ssl_, rc);
            if (ssl_err != SSL_ERROR_WANT_READ &&
                ssl_err != SSL_ERROR_WANT_WRITE) {
                handshake_error_ = "SSL_connect failed: " + drain_ssl_errors();
                *err = handshake_error_;
                return false;
            }
            int wait_ms = static_cast<int>(std::chrono::duration_cast<
                std::chrono::milliseconds>(deadline -
                    std::chrono::steady_clock::now()).count());
            if (wait_ms <= 0) {
                handshake_error_ = "TLS handshake timed out";
                *err = handshake_error_;
                return false;
            }
            epoll_event ev{};
            ev.events = (ssl_err == SSL_ERROR_WANT_WRITE)
                        ? (EPOLLOUT | EPOLLIN)
                        : EPOLLIN;
            ev.data.fd = fd_;
            epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, fd_, &ev);

            epoll_event events[2];
            int n = epoll_wait(epoll_fd_, events, 2, wait_ms);
            if (n < 0) {
                if (errno == EINTR) continue;
                handshake_error_ = std::string("epoll_wait failed: ") + strerror(errno);
                *err = handshake_error_;
                return false;
            }
            // Drain wake_fd if signalled (no-op during handshake).
            for (int i = 0; i < n; ++i) {
                if (events[i].data.fd == wake_fd_) {
                    uint64_t c; ssize_t r = ::read(wake_fd_, &c, sizeof(c));
                    (void)r;
                }
            }
        }
    }

    void capture_session_info() {
        const char* proto = SSL_get_version(ssl_);
        if (proto) tls_protocol_ = proto;
        const SSL_CIPHER* cipher = SSL_get_current_cipher(ssl_);
        if (cipher) {
            const char* name = SSL_CIPHER_get_name(cipher);
            if (name) tls_cipher_suite_ = name;
        }
        const unsigned char* alpn = nullptr;
        unsigned int alpn_len = 0;
        SSL_get0_alpn_selected(ssl_, &alpn, &alpn_len);
        if (alpn && alpn_len > 0) {
            alpn_selected_.assign(reinterpret_cast<const char*>(alpn), alpn_len);
        }
        // Peer cert chain (DER-encoded). SSL_get_peer_cert_chain() works on
        // both 1.1 and 3.0; the chain does NOT include the leaf, so we
        // fetch the leaf separately below. SSL_get_peer_certificate() was
        // renamed to SSL_get1_peer_certificate() in 3.0 — `no-deprecated`
        // drops the legacy alias.
        STACK_OF(X509)* chain = SSL_get_peer_cert_chain(ssl_);
#if OPENSSL_VERSION_NUMBER >= 0x30000000L
        X509* leaf = SSL_get1_peer_certificate(ssl_);
#else
        X509* leaf = SSL_get_peer_certificate(ssl_);
#endif
        if (leaf) {
            int len = i2d_X509(leaf, nullptr);
            if (len > 0) {
                std::vector<uint8_t> der(static_cast<size_t>(len));
                uint8_t* p = der.data();
                if (i2d_X509(leaf, &p) > 0) {
                    peer_cert_chain_.push_back(std::move(der));
                }
            }
            X509_free(leaf);
        }
        if (chain) {
            int count = sk_X509_num(chain);
            for (int i = 0; i < count; ++i) {
                X509* c = sk_X509_value(chain, i);
                if (!c) continue;
                int len = i2d_X509(c, nullptr);
                if (len <= 0) continue;
                std::vector<uint8_t> der(static_cast<size_t>(len));
                uint8_t* p = der.data();
                if (i2d_X509(c, &p) > 0) {
                    peer_cert_chain_.push_back(std::move(der));
                }
            }
        }
    }

    void run_io_loop() {
        std::vector<uint8_t> read_scratch(kIoBufSize);
        bool want_write = false;
        bool ssl_wants_read = false;
        bool ssl_wants_write = false;

        while (running_.load(std::memory_order_acquire)) {
            // Decide what events to wait for. Always listen for reads
            // unless OpenSSL explicitly only wants write (rare on the
            // read side; we still want to surface peer EOF).
            uint32_t mask = EPOLLIN;
            bool tx_pending;
            {
                std::lock_guard<std::mutex> lk(tx_mu_);
                tx_pending = !tx_queue_.empty();
            }
            if (tx_pending || ssl_wants_write || want_write) {
                mask |= EPOLLOUT;
            }
            epoll_event ev{};
            ev.events = mask;
            ev.data.fd = fd_;
            epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, fd_, &ev);

            epoll_event events[2];
            int n = epoll_wait(epoll_fd_, events, 2, 1000);
            if (n < 0) {
                if (errno == EINTR) continue;
                fail_with(std::string("epoll_wait failed: ") + strerror(errno));
                return;
            }
            for (int i = 0; i < n; ++i) {
                if (events[i].data.fd == wake_fd_) {
                    uint64_t c; ssize_t r = ::read(wake_fd_, &c, sizeof(c));
                    (void)r;
                }
            }

            // Always try reads — SSL_read may have buffered cleartext
            // even if the socket itself reports no more data.
            ssl_wants_read = false;
            while (true) {
                int got = SSL_read(ssl_, read_scratch.data(),
                                   static_cast<int>(read_scratch.size()));
                if (got > 0) {
                    std::vector<uint8_t> chunk(
                        read_scratch.data(),
                        read_scratch.data() + got);
                    {
                        std::lock_guard<std::mutex> lk(rx_mu_);
                        rx_queue_.emplace_back();
                        rx_queue_.back().data = std::move(chunk);
                    }
                    rx_cv_.notify_one();
                    continue;
                }
                int sslerr = SSL_get_error(ssl_, got);
                if (sslerr == SSL_ERROR_WANT_READ) {
                    ssl_wants_read = true;
                    break;
                }
                if (sslerr == SSL_ERROR_WANT_WRITE) {
                    ssl_wants_write = true;
                    break;
                }
                if (sslerr == SSL_ERROR_ZERO_RETURN) {
                    // Clean TLS shutdown from the peer.
                    signal_eof();
                    return;
                }
                fail_with("SSL_read failed: " + drain_ssl_errors());
                return;
            }

            // Try writes — pull from tx_queue and SSL_write until it
            // wants more space or the queue empties.
            ssl_wants_write = false;
            want_write = false;
            while (true) {
                const uint8_t* chunk_data = nullptr;
                size_t chunk_size = 0;
                size_t chunk_offset = 0;
                {
                    std::lock_guard<std::mutex> lk(tx_mu_);
                    if (tx_queue_.empty()) {
                        if (tx_closed_ && !tls_shutdown_sent_) {
                            // No more user data — send TLS close_notify
                            // and exit. SSL_shutdown can WANT_WRITE
                            // first call; loop below handles it.
                            tls_shutdown_sent_ = true;
                            (void)SSL_shutdown(ssl_);
                            signal_eof();
                            return;
                        }
                        break;
                    }
                    const auto& front = tx_queue_.front();
                    chunk_data = front.data.data();
                    chunk_size = front.data.size();
                    chunk_offset = front.offset;
                }
                if (chunk_offset >= chunk_size) {
                    std::lock_guard<std::mutex> lk(tx_mu_);
                    if (!tx_queue_.empty() &&
                        tx_queue_.front().offset >= tx_queue_.front().data.size()) {
                        tx_queue_.pop_front();
                    }
                    continue;
                }
                size_t to_write = chunk_size - chunk_offset;
                int rc = SSL_write(ssl_,
                                   chunk_data + chunk_offset,
                                   static_cast<int>(to_write));
                if (rc > 0) {
                    {
                        std::lock_guard<std::mutex> lk(tx_mu_);
                        if (!tx_queue_.empty()) {
                            auto& front = tx_queue_.front();
                            front.offset += static_cast<size_t>(rc);
                            tx_buffered_ -= static_cast<size_t>(rc);
                            if (front.offset >= front.data.size()) {
                                tx_queue_.pop_front();
                            }
                        }
                    }
                    // Wake any producer that was blocked on backpressure.
                    // notify_all is cheap and correct under bursty drain.
                    tx_space_cv_.notify_all();
                    continue;
                }
                int sslerr = SSL_get_error(ssl_, rc);
                if (sslerr == SSL_ERROR_WANT_WRITE) {
                    ssl_wants_write = true;
                    break;
                }
                if (sslerr == SSL_ERROR_WANT_READ) {
                    ssl_wants_read = true;
                    break;
                }
                fail_with("SSL_write failed: " + drain_ssl_errors());
                return;
            }
            (void)ssl_wants_read;
        }
    }

    void fail_with(std::string reason) {
        LOGW("yume native tls fail: %s", reason.c_str());
        transport_failed_.store(true);
        {
            std::lock_guard<std::mutex> lk(rx_mu_);
            if (rx_error_.empty()) {
                rx_error_ = std::move(reason);
            }
            rx_eof_ = true;
        }
        rx_cv_.notify_all();
        tx_space_cv_.notify_all();  // unblock any producer waiting on space
    }

    void signal_eof() {
        std::lock_guard<std::mutex> lk(rx_mu_);
        rx_eof_ = true;
        rx_cv_.notify_all();
    }

    SSL_CTX* ctx_{nullptr};
    SSL*     ssl_{nullptr};
    int      fd_{-1};
    int      epoll_fd_{-1};
    int      wake_fd_{-1};
    std::thread worker_;
    std::atomic<bool> running_{false};

    std::string sni_host_;
    std::string alpn_proto_;
    std::string tls_protocol_;
    std::string tls_cipher_suite_;
    std::string alpn_selected_;
    std::string handshake_error_;
    std::vector<std::vector<uint8_t>> peer_cert_chain_;

    std::mutex tx_mu_;
    std::condition_variable tx_space_cv_;  // signals producer when queue drains
    std::deque<TxChunk> tx_queue_;
    size_t   tx_buffered_{0};
    bool     tx_closed_{false};
    bool     tls_shutdown_sent_{false};
    // Sticky failure flag — read without holding tx_mu_ from write() so a
    // blocked producer can give up promptly when the worker fails. Set
    // from fail_with() (worker thread).
    std::atomic<bool> transport_failed_{false};

    std::mutex rx_mu_;
    std::condition_variable rx_cv_;
    std::deque<TxChunk> rx_queue_;
    bool     rx_eof_{false};
    std::string rx_error_;
};

// JNI-side helpers.
TlsPipe* handle_to_pipe(jlong handle) {
    return reinterpret_cast<TlsPipe*>(static_cast<uintptr_t>(handle));
}

std::string jstring_to_utf8(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(js, chars);
    return out;
}

void throw_runtime_exception(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeCreate(
    JNIEnv* env, jclass /*cls*/,
    jstring sniHost,
    jstring trustPem,
    jboolean useSystemTrust,
    jstring alpn) {
    auto* pipe = new (std::nothrow) TlsPipe();
    if (!pipe) {
        throw_runtime_exception(env, "OOM allocating TlsPipe");
        return 0;
    }
    std::string err;
    if (!pipe->configure(jstring_to_utf8(env, sniHost),
                         jstring_to_utf8(env, trustPem),
                         useSystemTrust == JNI_TRUE,
                         jstring_to_utf8(env, alpn),
                         &err)) {
        delete pipe;
        throw_runtime_exception(env, std::string("native tls configure failed: ") + err);
        return 0;
    }
    return reinterpret_cast<jlong>(pipe);
}

JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeStart(
    JNIEnv* env, jclass /*cls*/,
    jlong handle, jint fd, jint handshakeTimeoutMs) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return -1;
    std::string err;
    int rc = pipe->start(static_cast<int>(fd),
                         static_cast<int>(handshakeTimeoutMs), &err);
    if (rc != 0) {
        throw_runtime_exception(env, "native tls handshake failed: " + err);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeWrite(
    JNIEnv* env, jclass /*cls*/,
    jlong handle, jbyteArray data, jint offset, jint length) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return -1;
    if (length <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;
    int rc = pipe->write(
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeRead(
    JNIEnv* env, jclass /*cls*/,
    jlong handle, jbyteArray dst, jint offset, jint maxLength, jint timeoutMs) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return -2;
    if (maxLength <= 0) return 0;
    std::vector<uint8_t> scratch(static_cast<size_t>(maxLength));
    std::string err;
    int rc = pipe->read(scratch.data(), scratch.size(),
                        static_cast<int>(timeoutMs), &err);
    if (rc > 0) {
        env->SetByteArrayRegion(
            dst, offset, rc,
            reinterpret_cast<const jbyte*>(scratch.data()));
    }
    if (rc == -2 && !err.empty()) {
        // Side-channel the error message via a thrown exception so the
        // Kotlin side can surface it.
        throw_runtime_exception(env, "native tls read failed: " + err);
    }
    return rc;
}

JNIEXPORT void JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeClose(
    JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return;
    pipe->close();
}

JNIEXPORT void JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeDestroy(
    JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* pipe = handle_to_pipe(handle);
    delete pipe;
}

JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeGetTlsProtocol(
    JNIEnv* env, jclass /*cls*/, jlong handle) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return env->NewStringUTF("");
    return env->NewStringUTF(pipe->tls_protocol().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeGetTlsCipherSuite(
    JNIEnv* env, jclass /*cls*/, jlong handle) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return env->NewStringUTF("");
    return env->NewStringUTF(pipe->tls_cipher_suite().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeGetAlpnSelected(
    JNIEnv* env, jclass /*cls*/, jlong handle) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return env->NewStringUTF("");
    return env->NewStringUTF(pipe->alpn_selected().c_str());
}

JNIEXPORT jint JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeGetPeerCertCount(
    JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe) return 0;
    return static_cast<jint>(pipe->peer_cert_count());
}

JNIEXPORT jbyteArray JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeGetPeerCertDer(
    JNIEnv* env, jclass /*cls*/, jlong handle, jint index) {
    auto* pipe = handle_to_pipe(handle);
    if (!pipe || index < 0 ||
        static_cast<size_t>(index) >= pipe->peer_cert_count()) {
        return nullptr;
    }
    const auto& der = pipe->peer_cert(static_cast<size_t>(index));
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(der.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(
        arr, 0, static_cast<jsize>(der.size()),
        reinterpret_cast<const jbyte*>(der.data()));
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_fixcraft_yume_transport_YumeNativeTlsPipe_nativeOpenSslVersion(
    JNIEnv* env, jclass /*cls*/) {
    return env->NewStringUTF(OpenSSL_version(OPENSSL_VERSION));
}

}  // extern "C"
