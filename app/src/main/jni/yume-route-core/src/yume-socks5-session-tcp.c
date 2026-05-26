/*
 ============================================================================
 Name        : yume-socks5-session-tcp.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume SOCKS5 Session TCP
 ============================================================================
 */

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string.h>

#ifdef ANDROID
#include <android/log.h>
#define YUME_TCP_LOGI(...) __android_log_print (ANDROID_LOG_INFO, "yume-route-core", __VA_ARGS__)
#define YUME_TCP_LOGW(...) __android_log_print (ANDROID_LOG_WARN, "yume-route-core", __VA_ARGS__)
#else
#define YUME_TCP_LOGI(...) LOG_I (__VA_ARGS__)
#define YUME_TCP_LOGW(...) LOG_W (__VA_ARGS__)
#endif

#include <lwip/tcp.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>
#include <yume-task-mutex.h>
#include <yume-memory-allocator.h>
#include <yume-socks5-misc.h>

#include "yume-utils.h"
#include "yume-route-config.h"
#include "yume-logger.h"
#include "yume-route-config-const.h"
#include "yume-route-core.h"
#include "yume-native-transport.h"

#include "yume-socks5-session-tcp.h"

#define TCP_DIRECT_POLL_TIMEOUT_MS 10
#define TCP_DIRECT_STREAM_READ_TIMEOUT_MS 100
#define TCP_DIRECT_WRITE_WAIT_MS 10

static void yume_socks5_session_tcp_splice (YumeSocks5Session *base);

static int
task_io_yielder (YumeTaskYieldType type, void *data)
{
    YumeSocks5Session *self = data;
    YumeListNode *node;
    int res;

    res = yume_socks5_task_io_yielder (type, data);
    node = yume_socks5_session_get_node (self);
    yume_route_update_session (node);

    return res;
}

static int
tcp_session_stopping (YumeSocks5SessionTCP *self)
{
    return yume_socks5_get_timeout (YUME_SOCKS5 (self)) == 0;
}

static int
tcp_endpoint_into_host_port (const YumeSocks5Addr *addr, char *host,
                             size_t host_len, int *port)
{
    uint16_t net_port;

    switch (addr->atype) {
    case YUME_SOCKS5_ADDR_TYPE_IPV4:
        if (!inet_ntop (AF_INET, addr->ipv4.addr, host, host_len))
            return -1;
        net_port = addr->ipv4.port;
        break;
    case YUME_SOCKS5_ADDR_TYPE_IPV6:
        if (!inet_ntop (AF_INET6, addr->ipv6.addr, host, host_len))
            return -1;
        net_port = addr->ipv6.port;
        break;
    case YUME_SOCKS5_ADDR_TYPE_NAME:
        if (addr->domain.len >= host_len)
            return -1;
        memcpy (host, addr->domain.addr, addr->domain.len);
        host[addr->domain.len] = '\0';
        memcpy (&net_port, addr->domain.addr + addr->domain.len, 2);
        break;
    default:
        return -1;
    }

    *port = ntohs (net_port);
    return 0;
}

static int
tcp_direct_set_nonblocking (int fd)
{
    int nonblock = 1;

    return ioctl (fd, FIONBIO, (char *)&nonblock);
}

static void
tcp_direct_tune_socket_buffer (int fd)
{
    int size = yume_route_config_get_misc_tcp_buffer_size ();

    if (size < 262144)
        size = 262144;
    setsockopt (fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof (size));
    setsockopt (fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof (size));
}

static int
tcp_direct_wait_writable (YumeSocks5SessionTCP *self, int fd)
{
    struct pollfd pfd = { 0 };
    int res;

    pfd.fd = fd;
    pfd.events = POLLOUT | POLLERR | POLLHUP | POLLNVAL;

    do {
        res = poll (&pfd, 1, TCP_DIRECT_WRITE_WAIT_MS);
    } while (res < 0 && errno == EINTR && !self->direct_stop &&
             !tcp_session_stopping (self));

    if (res < 0)
        return -1;
    if (res == 0)
        return 0;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))
        return -1;

    return 0;
}

static int
tcp_direct_send_all (YumeSocks5SessionTCP *self, int fd,
                     const unsigned char *data, size_t len)
{
    size_t sent = 0;

    while (sent < len && !self->direct_stop && !tcp_session_stopping (self)) {
        ssize_t res = send (fd, data + sent, len - sent, MSG_NOSIGNAL);
        if (res < 0) {
            if (errno == EINTR)
                continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (tcp_direct_wait_writable (self, fd) < 0)
                    return -1;
                continue;
            }
            return -1;
        }
        if (res == 0)
            return -1;
        sent += res;
    }

    return sent == len ? 0 : -1;
}

static int
tcp_direct_forward_peer_data (YumeSocks5SessionTCP *self, unsigned char *buffer,
                              size_t buffer_len)
{
    while (!self->direct_stop && !tcp_session_stopping (self)) {
        ssize_t read_len;

        read_len = recv (self->direct_peer_fd, buffer, buffer_len, MSG_DONTWAIT);
        if (read_len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK)
                return 0;
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (read_len == 0)
            return -1;
        if (yume_native_transport_send_direct (self->direct_stream_handle,
                                            buffer, read_len) < 0)
            return -1;
        if ((size_t)read_len < buffer_len)
            return 0;
    }

    return 0;
}

static int
tcp_direct_forward_stream_data (YumeSocks5SessionTCP *self, unsigned char *buffer,
                                size_t buffer_len)
{
    while (!self->direct_stop && !tcp_session_stopping (self)) {
        ssize_t incoming_len;

        incoming_len = yume_native_transport_read_into (
            self->direct_stream_handle, buffer, buffer_len, 0);
        if (incoming_len < 0)
            return -1;
        if (incoming_len == 0)
            return 0;
        if (tcp_direct_send_all (self, self->direct_peer_fd, buffer,
                                 incoming_len) < 0)
            return -1;
        if ((size_t)incoming_len < buffer_len)
            return 0;
    }

    return 0;
}

static void *
tcp_direct_downstream_entry (void *data)
{
    YumeSocks5SessionTCP *self = data;
    unsigned char recv_buf[65536];

    while (!self->direct_stop && !tcp_session_stopping (self)) {
        ssize_t incoming_len;

        incoming_len = yume_native_transport_read_into (
            self->direct_stream_handle, recv_buf, sizeof (recv_buf),
            TCP_DIRECT_STREAM_READ_TIMEOUT_MS);
        if (incoming_len < 0)
            break;
        if (incoming_len == 0)
            continue;
        if (tcp_direct_send_all (self, self->direct_peer_fd, recv_buf,
                                 incoming_len) < 0)
            break;
    }

    self->direct_stop = 1;
    if (self->direct_peer_fd >= 0)
        shutdown (self->direct_peer_fd, SHUT_RDWR);
    return NULL;
}

static void *
tcp_direct_relay_entry (void *data)
{
    YumeSocks5SessionTCP *self = data;
    unsigned char send_buf[65536];
    pthread_t downstream_thread;
    int downstream_started = 0;

    YUME_TCP_LOGI ("native-direct TCP opening %s:%d", self->direct_host,
                   self->direct_port);
    self->direct_stream_handle = yume_native_transport_open (
        self->direct_host, self->direct_port, "tcp", self->direct_timeout_ms);

    pthread_mutex_lock (&self->direct_mutex);
    self->direct_open_status = self->direct_stream_handle > 0 ? 0 : -1;
    self->direct_open_done = 1;
    pthread_cond_signal (&self->direct_cond);
    pthread_mutex_unlock (&self->direct_mutex);

    if (self->direct_stream_handle <= 0) {
        YUME_TCP_LOGW ("native-direct TCP open failed %s:%d", self->direct_host,
                       self->direct_port);
        shutdown (self->direct_peer_fd, SHUT_RDWR);
        return NULL;
    }

    YUME_TCP_LOGI ("native-direct TCP stream queued %s:%d handle=%d",
                   self->direct_host, self->direct_port,
                   self->direct_stream_handle);

    if (pthread_create (&downstream_thread, NULL, tcp_direct_downstream_entry,
                        self) == 0) {
        downstream_started = 1;
    } else {
        YUME_TCP_LOGW ("native-direct TCP downstream thread failed %s:%d",
                       self->direct_host, self->direct_port);
        shutdown (self->direct_peer_fd, SHUT_RDWR);
        self->direct_stop = 1;
    }

    while (!self->direct_stop && !tcp_session_stopping (self)) {
        struct pollfd pfd = { 0 };
        int poll_res;

        pfd.fd = self->direct_peer_fd;
        pfd.events = POLLIN | POLLERR | POLLHUP | POLLNVAL;
        poll_res = poll (&pfd, 1, TCP_DIRECT_POLL_TIMEOUT_MS);
        if (poll_res > 0) {
            if (pfd.revents & POLLIN) {
                if (tcp_direct_forward_peer_data (self, send_buf,
                                                  sizeof (send_buf)) < 0)
                    break;
            }

            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))
                break;
        } else if (poll_res < 0 && errno != EINTR) {
            break;
        }
    }

    self->direct_stop = 1;
    shutdown (self->direct_peer_fd, SHUT_RDWR);
    if (downstream_started)
        pthread_join (downstream_thread, NULL);

    if (self->direct_stream_handle > 0) {
        yume_native_transport_close_stream (self->direct_stream_handle,
                                         self->direct_stop ?
                                             "tcp direct session stopping" :
                                             "tcp direct relay closed");
        self->direct_stream_handle = -1;
    }

    shutdown (self->direct_peer_fd, SHUT_RDWR);
    return NULL;
}

static void
tcp_direct_stop (YumeSocks5SessionTCP *self, const char *reason)
{
    int had_direct = self->direct_relay_started || self->direct_peer_fd >= 0 ||
                     self->direct_stream_handle > 0;

    if (!had_direct)
        return;

    self->direct_stop = 1;

    if (self->direct_peer_fd >= 0)
        shutdown (self->direct_peer_fd, SHUT_RDWR);

    if (self->direct_relay_started) {
        pthread_join (self->direct_relay_thread, NULL);
        self->direct_relay_started = 0;
    }

    self->direct_stream_handle = -1;
    self->direct_open_done = 0;
    self->direct_open_status = -1;

    if (self->direct_peer_fd >= 0) {
        close (self->direct_peer_fd);
        self->direct_peer_fd = -1;
    }

    if (YUME_SOCKS5 (self)->fd >= 0) {
        if (self->data.task)
            yume_task_del_fd (self->data.task, YUME_SOCKS5 (self)->fd);
        close (YUME_SOCKS5 (self)->fd);
        YUME_SOCKS5 (self)->fd = -1;
    }
}

static int
tcp_direct_start (YumeSocks5SessionTCP *self)
{
    int fds[2] = { -1, -1 };

    if (tcp_endpoint_into_host_port (self->base.addr, self->direct_host,
                                     sizeof (self->direct_host),
                                     &self->direct_port) < 0)
        return -1;

    if (socketpair (AF_UNIX, SOCK_STREAM, 0, fds) < 0)
        return -1;

    if (tcp_direct_set_nonblocking (fds[0]) < 0 ||
        tcp_direct_set_nonblocking (fds[1]) < 0)
        goto fail;
    tcp_direct_tune_socket_buffer (fds[0]);
    tcp_direct_tune_socket_buffer (fds[1]);

    self->direct_peer_fd = fds[1];
    self->direct_stream_handle = -1;
    self->direct_stop = 0;
    self->direct_open_done = 0;
    self->direct_open_status = -1;
    self->direct_timeout_ms = yume_route_config_get_misc_connect_timeout ();
    YUME_SOCKS5 (self)->fd = fds[0];
    if (self->data.task) {
        if (yume_task_add_fd (self->data.task, YUME_SOCKS5 (self)->fd,
                             POLLIN | POLLOUT) < 0)
            yume_task_mod_fd (self->data.task, YUME_SOCKS5 (self)->fd,
                             POLLIN | POLLOUT);
    }
    fds[0] = -1;
    fds[1] = -1;

    if (pthread_create (&self->direct_relay_thread, NULL, tcp_direct_relay_entry,
                        self) != 0)
        goto fail;

    self->direct_relay_started = 1;
    YUME_TCP_LOGI (
        "native-direct TCP accepted %s:%d; splicing while Yume stream opens",
        self->direct_host, self->direct_port);

    return 0;

fail:
    tcp_direct_stop (self, "direct tcp start failed");
    if (fds[0] >= 0)
        close (fds[0]);
    if (fds[1] >= 0)
        close (fds[1]);
    return -1;
}

static int
tcp_splice_f (YumeSocks5SessionTCP *self)
{
    struct iovec iov[64];
    struct pbuf *p;
    int iovc = 0;
    int res = 1;

    if (self->queue) {
        for (p = self->queue; p && (iovc < 64); p = p->next, iovc++) {
            iov[iovc].iov_base = p->payload;
            iov[iovc].iov_len = p->len;
        }
    } else if (self->pcb_eof) {
        res = -1;
    } else {
        res = 0;
    }

    if (iovc) {
        ssize_t s = writev (YUME_SOCKS5 (self)->fd, iov, iovc);
        if (0 >= s) {
            if ((0 > s) && (EAGAIN == errno))
                res = 0;
            else
                res = -1;
        } else {
            yume_task_mutex_lock (self->mutex);
            self->queue = pbuf_free_header (self->queue, s);
            if (self->pcb)
                tcp_recved (self->pcb, s);
            yume_task_mutex_unlock (self->mutex);
            res = 1;
        }
    } else if (res < 0) {
        shutdown (YUME_SOCKS5 (self)->fd, SHUT_WR);
    }

    return res;
}

static int
tcp_splice_b (YumeSocks5SessionTCP *self)
{
    struct iovec iov[2];
    err_t err = ERR_OK;
    int res = 1, iovc;

    iovc = yume_ring_buffer_writing (self->buffer, iov);
    if (iovc) {
        ssize_t s = readv (YUME_SOCKS5 (self)->fd, iov, iovc);
        if (0 >= s) {
            if ((0 > s) && (EAGAIN == errno))
                res = 0;
            else
                res = -1;
        } else {
            yume_ring_buffer_write_finish (self->buffer, s);
        }
    } else {
        res = 0;
    }

    yume_task_mutex_lock (self->mutex);
    if (self->pcb) {
        iovc = yume_ring_buffer_reading (self->buffer, iov);
        if (iovc) {
            size_t s = 0;
            int i;

            for (i = 0; i < iovc; i++) {
                unsigned char *ptr = iov[i].iov_base;
                size_t len = iov[i].iov_len;

                while (len > 0) {
                    u16_t available = tcp_sndbuf (self->pcb);
                    u16_t chunk;
                    u8_t flags = 0;

                    if (available == 0 ||
                        tcp_sndqueuelen (self->pcb) >= TCP_SND_QUEUELEN)
                        goto tcp_send_blocked;

                    chunk = available;
                    if ((size_t)chunk > len)
                        chunk = (u16_t)len;
                    if (chunk == 0xffffU && len > 0xffffU)
                        flags |= TCP_WRITE_FLAG_MORE;
                    if ((size_t)chunk < len || i + 1 < iovc)
                        flags |= TCP_WRITE_FLAG_MORE;

                    err = tcp_write (self->pcb, ptr, chunk, flags);
                    if (err == ERR_MEM)
                        goto tcp_send_blocked;
                    if (err != ERR_OK)
                        goto tcp_send_failed;

                    ptr += chunk;
                    len -= chunk;
                    s += chunk;
                }
            }
tcp_send_blocked:
            if (s > 0) {
                err_t out_err;

                yume_ring_buffer_read_finish (self->buffer, s);
                out_err = tcp_output (self->pcb);
                if (out_err != ERR_OK && out_err != ERR_MEM)
                    err = out_err;
                res = err == ERR_OK || err == ERR_MEM ? 1 : -1;
            } else {
                res = 0;
            }
            goto tcp_send_done;

tcp_send_failed:
            res = -1;

tcp_send_done:
            ;
        } else if (res < 0) {
            tcp_shutdown (self->pcb, 0, 1);
        }
    }
    yume_task_mutex_unlock (self->mutex);
    if (!self->pcb || (err != ERR_OK && err != ERR_MEM))
        res = -1;

    return res;
}

static err_t
tcp_recv_handler (void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err)
{
    YumeSocks5SessionTCP *self = arg;

    if (p) {
        if (!self->queue) {
            self->queue = p;
        } else {
            u32_t wnd_max = TCP_WND_MAX (pcb);
            if (wnd_max < 0xffffU && (u32_t)self->queue->tot_len > wnd_max)
                return ERR_WOULDBLOCK;
            pbuf_cat (self->queue, p);
        }
    } else {
        self->pcb_eof = 1;
    }

    yume_task_wakeup (self->data.task);
    return ERR_OK;
}

static err_t
tcp_sent_handler (void *arg, struct tcp_pcb *pcb, u16_t len)
{
    YumeSocks5SessionTCP *self = arg;

    yume_ring_buffer_read_release (self->buffer, len);
    yume_task_wakeup (self->data.task);

    return ERR_OK;
}

static void
tcp_err_handler (void *arg, err_t err)
{
    YumeSocks5SessionTCP *self = arg;

    self->pcb = NULL;
    yume_socks5_session_terminate (YUME_SOCKS5_SESSION (self));
}

YumeSocks5SessionTCP *
yume_socks5_session_tcp_new (struct tcp_pcb *pcb, YumeTaskMutex *mutex)
{
    YumeSocks5SessionTCP *self;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5SessionTCP));
    if (!self)
        return NULL;

    res = yume_socks5_session_tcp_construct (self, pcb, mutex);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p Yume SOCKS5 session tcp new", self);

    return self;
}

static int
yume_socks5_session_tcp_bind (YumeSocks5 *self, int fd,
                             const struct sockaddr *dest)
{
    YumeRouteConfigServer *srv;
    unsigned int mark;

    LOG_D ("%p Yume SOCKS5 session tcp bind", self);

    srv = yume_route_config_get_socks5_server ();
    mark = srv->mark;

    if (mark) {
        int res;

        res = set_sock_mark (fd, mark);
        if (res < 0)
            return -1;
    }

    return 0;
}

static void
yume_socks5_session_tcp_run (YumeSocks5Session *base)
{
    YumeSocks5SessionTCP *self = YUME_SOCKS5_SESSION_TCP (base);

    if (yume_native_transport_is_direct_enabled ()) {
        if (tcp_direct_start (self) == 0) {
            yume_socks5_session_tcp_splice (base);
            tcp_direct_stop (self, "tcp direct session closed");
            return;
        }

        tcp_direct_stop (self, "tcp direct setup failed");
        LOG_W ("%p direct tcp setup failed; closing native-direct flow", self);
        return;
    }

    yume_socks5_session_run_via_socks (base);
}

static void
yume_socks5_session_tcp_splice (YumeSocks5Session *base)
{
    YumeSocks5SessionTCP *self = YUME_SOCKS5_SESSION_TCP (base);
    int tcp_buffer_size;
    int res_f = 1;
    int res_b = 1;

    LOG_D ("%p Yume SOCKS5 session tcp splice", self);

    if (!self->pcb)
        return;

    tcp_buffer_size = yume_route_config_get_misc_tcp_buffer_size ();
    self->buffer = yume_ring_buffer_alloca (tcp_buffer_size);
    if (!self->buffer)
        return;

    for (;;) {
        YumeTaskYieldType type;

        if (res_f >= 0)
            res_f = tcp_splice_f (self);
        if (res_b >= 0)
            res_b = tcp_splice_b (self);

        if (res_f > 0 || res_b > 0)
            type = YUME_TASK_YIELD;
        else if ((res_f & res_b) == 0)
            type = YUME_TASK_WAITIO;
        else
            break;

        if (task_io_yielder (type, base) < 0)
            break;
    }

    while (self->pcb) {
        if (yume_ring_buffer_get_use_size (self->buffer) == 0)
            break;

        if (task_io_yielder (YUME_TASK_WAITIO, base) < 0)
            break;
    }
}

static YumeTask *
yume_socks5_session_tcp_get_task (YumeSocks5Session *base)
{
    YumeSocks5SessionTCP *self = YUME_SOCKS5_SESSION_TCP (base);

    return self->data.task;
}

static void
yume_socks5_session_tcp_set_task (YumeSocks5Session *base, YumeTask *task)
{
    YumeSocks5SessionTCP *self = YUME_SOCKS5_SESSION_TCP (base);

    self->data.task = task;
}

static YumeListNode *
yume_socks5_session_tcp_get_node (YumeSocks5Session *base)
{
    YumeSocks5SessionTCP *self = YUME_SOCKS5_SESSION_TCP (base);

    return &self->data.node;
}

int
yume_socks5_session_tcp_construct (YumeSocks5SessionTCP *self,
                                  struct tcp_pcb *pcb, YumeTaskMutex *mutex)
{
    YumeSocks5Addr addr;
    int res;

    res = yume_socks5_addr_from_lwip (&addr, &pcb->local_ip, pcb->local_port);
    if (res < 0)
        return -1;

    res = yume_socks5_client_tcp_construct (&self->base, &addr);
    if (res < 0)
        return -1;

    LOG_D ("%p Yume SOCKS5 session tcp construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_SESSION_TCP_TYPE;

    tcp_arg (pcb, self);
    tcp_recv (pcb, tcp_recv_handler);
    tcp_sent (pcb, tcp_sent_handler);
    tcp_err (pcb, tcp_err_handler);

    self->pcb = pcb;
    self->mutex = mutex;
    self->data.self = self;
    pthread_mutex_init (&self->direct_mutex, NULL);
    pthread_cond_init (&self->direct_cond, NULL);
    self->direct_peer_fd = -1;
    self->direct_stream_handle = -1;
    self->direct_open_status = -1;

    return 0;
}

void
yume_socks5_session_tcp_destruct (YumeObject *base)
{
    YumeSocks5SessionTCP *self = YUME_SOCKS5_SESSION_TCP (base);

    LOG_D ("%p Yume SOCKS5 session tcp destruct", self);

    tcp_direct_stop (self, "tcp session destruct");
    pthread_cond_destroy (&self->direct_cond);
    pthread_mutex_destroy (&self->direct_mutex);

    yume_task_mutex_lock (self->mutex);
    if (self->pcb) {
        tcp_recv (self->pcb, NULL);
        tcp_sent (self->pcb, NULL);
        tcp_err (self->pcb, NULL);
        tcp_abort (self->pcb);
    }

    if (self->queue)
        pbuf_free (self->queue);
    yume_task_mutex_unlock (self->mutex);

    YUME_SOCKS5_CLIENT_TCP_TYPE->destruct (base);
}

static void *
yume_socks5_session_tcp_iface (YumeObject *base, void *type)
{
    if (type == YUME_SOCKS5_SESSION_TYPE) {
        YumeSocks5SessionTCPClass *klass = YUME_OBJECT_GET_CLASS (base);
        return &klass->session;
    }

    return YUME_SOCKS5_CLIENT_TCP_TYPE->iface (base, type);
}

YumeObjectClass *
yume_socks5_session_tcp_class (void)
{
    static YumeSocks5SessionTCPClass klass;
    YumeSocks5SessionTCPClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        YumeSocks5Class *skptr;
        YumeSocks5SessionIface *siptr;
        void *ptr;

        ptr = YUME_SOCKS5_CLIENT_TCP_TYPE;
        memcpy (kptr, ptr, sizeof (YumeSocks5ClientTCPClass));

        okptr->name = "YumeSocks5SessionTCP";
        okptr->destruct = yume_socks5_session_tcp_destruct;
        okptr->iface = yume_socks5_session_tcp_iface;

        skptr = YUME_SOCKS5_CLASS (kptr);
        skptr->binder = yume_socks5_session_tcp_bind;

        siptr = &kptr->session;
        siptr->run = yume_socks5_session_tcp_run;
        siptr->splicer = yume_socks5_session_tcp_splice;
        siptr->get_task = yume_socks5_session_tcp_get_task;
        siptr->set_task = yume_socks5_session_tcp_set_task;
        siptr->get_node = yume_socks5_session_tcp_get_node;
    }

    return okptr;
}
