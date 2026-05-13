/*
 ============================================================================
 Name        : yume-socks5-session-udp.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume SOCKS5 Session UDP
 ============================================================================
 */

#include <errno.h>
#include <arpa/inet.h>
#include <poll.h>
#include <pthread.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <lwip/udp.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>
#include <yume-task-mutex.h>
#include <yume-memory-allocator.h>
#include <yume-socks5-udp.h>
#include <yume-socks5-misc.h>

#include "yume-utils.h"
#include "yume-route-config.h"
#include "yume-logger.h"
#include "yume-compiler.h"
#include "yume-route-config-const.h"
#include "yume-route-core.h"
#include "yume-native-transport.h"
#include "yume-socks5-session-udp.h"

typedef struct _YumeSocks5UDPFrame YumeSocks5UDPFrame;

#define UDP_DIRECT_POLL_TIMEOUT_MS 2
#define UDP_DIRECT_STREAM_READ_TIMEOUT_MS 2
#define UDP_DIRECT_SOCKET_BUFFER_BYTES (256 * 1024)

struct _YumeSocks5UDPFrame
{
    YumeListNode node;
    YumeSocks5Addr addr;
    struct pbuf *data;
};

static void yume_socks5_session_udp_splice (YumeSocks5Session *base);

static int
udp_endpoint_into_host_port (const YumeSocks5Addr *addr, char *host,
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

static int udp_direct_start (YumeSocks5SessionUDP *self);
static void udp_direct_stop (YumeSocks5SessionUDP *self, const char *reason);

static int
udp_direct_send_frame_to_peer (YumeSocks5SessionUDP *self,
                               const unsigned char *payload, size_t payload_len)
{
    size_t frame_len;
    unsigned char *frame;
    ssize_t sent;

    if (!self->direct_addr || self->direct_addr_len <= 0)
        return -1;

    frame_len = 3 + self->direct_addr_len + payload_len;
    frame = yume_malloc (frame_len);
    if (!frame)
        return -1;

    memset (frame, 0, 3);
    memcpy (frame + 3, self->direct_addr, self->direct_addr_len);
    memcpy (frame + 3 + self->direct_addr_len, payload, payload_len);

    sent = send (self->direct_peer_fd, frame, frame_len, MSG_NOSIGNAL);
    yume_free (frame);

    return sent == (ssize_t)frame_len ? 0 : -1;
}

static int
udp_direct_forward_peer_datagrams (YumeSocks5SessionUDP *self,
                                   unsigned char *buffer, size_t buffer_len)
{
    while (!self->direct_stop && yume_socks5_get_timeout (YUME_SOCKS5 (self)) != 0) {
        YumeSocks5UDPHdr *udp = (YumeSocks5UDPHdr *)buffer;
        int addrlen;
        int payload_offset;
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
        if (read_len < 4)
            return -1;

        addrlen = yume_socks5_addr_len (&udp->addr);
        if (addrlen <= 0)
            return -1;

        payload_offset = 3 + addrlen;
        if (payload_offset > read_len)
            return -1;

        if (yume_native_transport_send (self->direct_stream_handle,
                                     buffer + payload_offset,
                                     read_len - payload_offset) < 0) {
            return -1;
        }

        if ((size_t)read_len < buffer_len)
            return 0;
    }

    return 0;
}

static int
udp_direct_forward_stream_datagrams (YumeSocks5SessionUDP *self, int timeout_ms)
{
    while (!self->direct_stop && yume_socks5_get_timeout (YUME_SOCKS5 (self)) != 0) {
        unsigned char *payload = NULL;
        ssize_t read_len;
        int res;

        read_len = yume_native_transport_read (self->direct_stream_handle, &payload,
                                            timeout_ms);
        if (read_len < 0) {
            if (payload)
                free (payload);
            return -1;
        }
        if (read_len == 0) {
            if (payload)
                free (payload);
            return 0;
        }

        res = udp_direct_send_frame_to_peer (self, payload, read_len);
        free (payload);
        if (res < 0)
            return -1;

        timeout_ms = 0;
    }

    return 0;
}

static void *
udp_direct_relay_entry (void *data)
{
    YumeSocks5SessionUDP *self = data;
    unsigned char buffer[UDP_BUF_SIZE + 320];

    self->direct_stream_handle = yume_native_transport_open (
        self->direct_host, self->direct_port, "udp", self->direct_timeout_ms);
    if (self->direct_stream_handle <= 0) {
        shutdown (self->direct_peer_fd, SHUT_RDWR);
        return NULL;
    }

    while (!self->direct_stop && yume_socks5_get_timeout (YUME_SOCKS5 (self)) != 0) {
        struct pollfd pfd = { 0 };
        int poll_res;

        pfd.fd = self->direct_peer_fd;
        pfd.events = POLLIN | POLLERR | POLLHUP | POLLNVAL;
        poll_res = poll (&pfd, 1, UDP_DIRECT_POLL_TIMEOUT_MS);
        if (poll_res > 0) {
            if (pfd.revents & POLLIN) {
                if (udp_direct_forward_peer_datagrams (self, buffer,
                                                       sizeof (buffer)) < 0)
                    break;
            }

            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))
                break;
        } else if (poll_res < 0 && errno != EINTR) {
            break;
        }

        if (udp_direct_forward_stream_datagrams (
                self, UDP_DIRECT_STREAM_READ_TIMEOUT_MS) < 0) {
            break;
        }
    }

    if (self->direct_stream_handle > 0) {
        yume_native_transport_close_stream (self->direct_stream_handle,
                                         self->direct_stop ?
                                             "udp direct session stopping" :
                                             "udp direct relay closed");
        self->direct_stream_handle = -1;
    }

    shutdown (self->direct_peer_fd, SHUT_RDWR);
    return NULL;
}

static void
udp_direct_stop (YumeSocks5SessionUDP *self, const char *reason)
{
    int had_direct = self->direct_relay_started || self->direct_peer_fd >= 0 ||
                     self->direct_stream_handle > 0 ||
                     YUME_SOCKS5_CLIENT_UDP (self)->fd >= 0;

    if (!had_direct)
        return;

    self->direct_stop = 1;

    if (self->direct_peer_fd >= 0)
        shutdown (self->direct_peer_fd, SHUT_RDWR);

    if (self->direct_relay_started) {
        pthread_join (self->direct_relay_thread, NULL);
        self->direct_relay_started = 0;
    }

    if (self->direct_stream_handle > 0) {
        yume_native_transport_close_stream (self->direct_stream_handle,
                                         reason ? reason :
                                             "udp direct transport stopping");
        self->direct_stream_handle = -1;
    }

    if (self->data.task && YUME_SOCKS5_CLIENT_UDP (self)->fd >= 0)
        yume_task_del_fd (self->data.task, YUME_SOCKS5_CLIENT_UDP (self)->fd);

    if (YUME_SOCKS5_CLIENT_UDP (self)->fd >= 0) {
        close (YUME_SOCKS5_CLIENT_UDP (self)->fd);
        YUME_SOCKS5_CLIENT_UDP (self)->fd = -1;
    }

    if (self->direct_peer_fd >= 0) {
        close (self->direct_peer_fd);
        self->direct_peer_fd = -1;
    }
}

static int
udp_direct_start (YumeSocks5SessionUDP *self)
{
    int buffer_size = UDP_DIRECT_SOCKET_BUFFER_BYTES;
    int fds[2] = { -1, -1 };

    if (!self->direct_addr || self->direct_addr_len <= 0)
        return -1;

    if (udp_endpoint_into_host_port (self->direct_addr, self->direct_host,
                                     sizeof (self->direct_host),
                                     &self->direct_port) < 0) {
        return -1;
    }

    if (socketpair (AF_UNIX, SOCK_DGRAM, 0, fds) < 0)
        return -1;

    setsockopt (fds[0], SOL_SOCKET, SO_SNDBUF, &buffer_size, sizeof (buffer_size));
    setsockopt (fds[0], SOL_SOCKET, SO_RCVBUF, &buffer_size, sizeof (buffer_size));
    setsockopt (fds[1], SOL_SOCKET, SO_SNDBUF, &buffer_size, sizeof (buffer_size));
    setsockopt (fds[1], SOL_SOCKET, SO_RCVBUF, &buffer_size, sizeof (buffer_size));

    self->direct_peer_fd = fds[1];
    self->direct_stream_handle = -1;
    self->direct_stop = 0;
    self->direct_timeout_ms = yume_route_config_get_misc_connect_timeout ();
    YUME_SOCKS5_CLIENT_UDP (self)->fd = fds[0];
    YUME_SOCKS5 (self)->udp_associated = 1;
    if (self->data.task) {
        if (yume_task_add_fd (self->data.task, YUME_SOCKS5_CLIENT_UDP (self)->fd,
                             POLLIN | POLLOUT) < 0)
            yume_task_mod_fd (self->data.task, YUME_SOCKS5_CLIENT_UDP (self)->fd,
                             POLLIN | POLLOUT);
    }
    fds[0] = -1;
    fds[1] = -1;

    if (pthread_create (&self->direct_relay_thread, NULL, udp_direct_relay_entry,
                        self) != 0)
        goto fail;

    self->direct_relay_started = 1;
    return 0;

fail:
    udp_direct_stop (self, "udp direct start failed");
    if (fds[0] >= 0)
        close (fds[0]);
    if (fds[1] >= 0)
        close (fds[1]);
    return -1;
}

static int
task_io_yielder (YumeTaskYieldType type, void *data)
{
    YumeSocks5 *self = data;
    YumeListNode *node;
    int res;

    if (self->type == YUME_SOCKS5_TYPE_UDP_IN_UDP) {
        ssize_t res;
        char buf;

        res = recv (self->fd, &buf, sizeof (buf), 0);
        if ((res == 0) || ((res < 0) && (errno != EAGAIN))) {
            yume_socks5_set_timeout (self, 0);
            return -1;
        }
    }

    res = yume_socks5_task_io_yielder (type, data);
    node = yume_socks5_session_get_node (YUME_SOCKS5_SESSION (self));
    yume_route_update_session (node);

    return res;
}

static int
yume_socks5_session_udp_fwd_f (YumeSocks5SessionUDP *self, unsigned int num)
{
    YumeSocks5UDPMsg msgv[num];
    YumeSocks5UDPFrame *frame;
    YumeListNode *node;
    struct pbuf *buf;
    int i, res;

    res = self->frames;
    if (res <= 0)
        return 0;

    res = (res > num) ? num : res;
    node = yume_list_first (&self->frame_list);
    for (i = 0; i < res; i++) {
        frame = container_of (node, YumeSocks5UDPFrame, node);
        node = yume_list_node_next (node);
        buf = frame->data;

        msgv[i].buf = buf->payload;
        msgv[i].len = buf->len;
        msgv[i].addr = &frame->addr;
    }

    res = yume_socks5_udp_sendmmsg (YUME_SOCKS5_UDP (self), msgv, res);
    if (res <= 0) {
        LOG_D ("%p Yume SOCKS5 session udp fwd f send", self);
        return -1;
    }

    for (i = 0; i < res; i++) {
        node = yume_list_first (&self->frame_list);
        frame = container_of (node, YumeSocks5UDPFrame, node);
        buf = frame->data;

        yume_list_del (&self->frame_list, node);
        yume_free (frame);
        pbuf_free (buf);
        self->frames--;
    }

    return 1;
}

static int
yume_socks5_session_udp_fwd_b (YumeSocks5SessionUDP *self, unsigned int num)
{
    char buf[UDP_BUF_SIZE * num];
    YumeSocks5UDPMsg msgv[num];
    int i, res;

    for (i = 0; i < num; i++) {
        msgv[i].buf = buf + UDP_BUF_SIZE * i;
        msgv[i].len = UDP_BUF_SIZE;
    }

    res = yume_socks5_udp_recvmmsg (YUME_SOCKS5_UDP (self), msgv, num, 1);
    if (res <= 0) {
        if (res == -1 && errno == EAGAIN)
            return 0;
        LOG_D ("%p Yume SOCKS5 session udp fwd b recv", self);
        return -1;
    }

    for (i = 0; i < res; i++) {
        ip_addr_t saddr;
        struct pbuf *b;
        uint16_t port;
        err_t err;
        int ret;

        if (self->addr && self->port) {
            ip_2_ip4 (&saddr)->addr = self->addr;
            port = self->port;
        } else {
            ret = yume_socks5_addr_into_lwip (msgv[i].addr, &saddr, &port);
            if (ret < 0) {
                LOG_D ("%p Yume SOCKS5 session udp fwd b addr", self);
                return -1;
            }
        }

        b = pbuf_alloc_reference (msgv[i].buf, msgv[i].len, PBUF_REF);
        if (!b) {
            LOG_D ("%p Yume SOCKS5 session udp fwd b buf", self);
            return -1;
        }

        yume_task_mutex_lock (self->mutex);
        err = udp_sendfrom (self->pcb, b, &saddr, port);
        yume_task_mutex_unlock (self->mutex);

        pbuf_free (b);
        if (err != ERR_OK) {
            LOG_D ("%p Yume SOCKS5 session udp fwd b send", self);
            return -1;
        }
    }

    return 1;
}

static void
udp_recv_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                  const ip_addr_t *addr, u16_t port)
{
    YumeSocks5SessionUDP *self = arg;
    YumeSocks5UDPFrame *frame;

    if (!p) {
        yume_socks5_session_terminate (YUME_SOCKS5_SESSION (self));
        return;
    }

    if (self->frames > UDP_POOL_SIZE) {
        pbuf_free (p);
        return;
    }

    frame = yume_malloc (sizeof (YumeSocks5UDPFrame));
    if (!frame) {
        pbuf_free (p);
        return;
    }

    frame->data = p;
    memset (&frame->node, 0, sizeof (frame->node));
    yume_socks5_addr_from_lwip (&frame->addr, &pcb->local_ip, pcb->local_port);

    if (frame->addr.atype == YUME_SOCKS5_ADDR_TYPE_NAME) {
        self->addr = ip_2_ip4 (&pcb->local_ip)->addr;
        self->port = pcb->local_port;
    }

    self->frames++;
    yume_list_add_tail (&self->frame_list, &frame->node);
    yume_task_wakeup (self->data.task);
}

YumeSocks5SessionUDP *
yume_socks5_session_udp_new (struct udp_pcb *pcb, YumeTaskMutex *mutex)
{
    YumeSocks5SessionUDP *self;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5SessionUDP));
    if (!self)
        return NULL;

    res = yume_socks5_session_udp_construct (self, pcb, mutex);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p Yume SOCKS5 session udp new", self);

    return self;
}

static int
yume_socks5_session_udp_bind (YumeSocks5 *self, int fd,
                             const struct sockaddr *dest)
{
    YumeRouteConfigServer *srv;
    unsigned int mark;

    LOG_D ("%p Yume SOCKS5 session udp bind", self);

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

static uint16_t
yume_socks5_addr_get_port (const YumeSocks5Addr *addr)
{
    uint16_t port = 0;

    switch (addr->atype) {
    case YUME_SOCKS5_ADDR_TYPE_IPV4:
        port = addr->ipv4.port;
        break;
    case YUME_SOCKS5_ADDR_TYPE_IPV6:
        port = addr->ipv6.port;
        break;
    case YUME_SOCKS5_ADDR_TYPE_NAME:
        memcpy (&port, addr->domain.addr + addr->domain.len, 2);
    }

    return port;
}

static int
yume_socks5_session_udp_set_upstream_addr (YumeSocks5Client *base,
                                          YumeSocks5Addr *addr)
{
    YumeRouteConfigServer *srv = yume_route_config_get_socks5_server ();
    YumeSocks5ClientClass *ckptr;

    if (srv->udp_in_udp && srv->udp_addr[0]) {
        uint16_t port = yume_socks5_addr_get_port (addr);
        yume_socks5_addr_from_name (addr, srv->udp_addr, port);
    }

    ckptr = YUME_SOCKS5_CLIENT_CLASS (YUME_SOCKS5_CLIENT_UDP_TYPE);
    return ckptr->set_upstream_addr (base, addr);
}

static void
yume_socks5_session_udp_run (YumeSocks5Session *base)
{
    YumeSocks5SessionUDP *self = YUME_SOCKS5_SESSION_UDP (base);

    if (yume_native_transport_is_direct_enabled ()) {
        if (udp_direct_start (self) == 0) {
            yume_socks5_session_udp_splice (base);
            udp_direct_stop (self, "udp direct session closed");
            return;
        }

        udp_direct_stop (self, "udp direct setup failed");
        LOG_W ("%p native-direct udp setup failed; closing flow", self);
        return;
    }

    yume_socks5_session_run_via_socks (base);
}

static void
yume_socks5_session_udp_splice (YumeSocks5Session *base)
{
    YumeSocks5SessionUDP *self = YUME_SOCKS5_SESSION_UDP (base);
    YumeTask *task = yume_task_self ();
    int res_f = 1, res_b = 1;
    int num;
    int fd;

    LOG_D ("%p Yume SOCKS5 session udp splice", self);

    num = yume_route_config_get_misc_udp_copy_buffer_nums ();
    fd = yume_socks5_udp_get_fd (YUME_SOCKS5_UDP (self));
    if (yume_task_mod_fd (task, fd, POLLIN | POLLOUT) < 0)
        yume_task_add_fd (task, fd, POLLIN | POLLOUT);

    for (;;) {
        YumeTaskYieldType type;

        if (res_f >= 0)
            res_f = yume_socks5_session_udp_fwd_f (self, num);
        if (res_b >= 0)
            res_b = yume_socks5_session_udp_fwd_b (self, num);

        if (res_f > 0 || res_b > 0)
            type = YUME_TASK_YIELD;
        else if ((res_f & res_b) == 0)
            type = YUME_TASK_WAITIO;
        else
            break;

        if (task_io_yielder (type, self))
            break;
    }
}

static YumeTask *
yume_socks5_session_udp_get_task (YumeSocks5Session *base)
{
    YumeSocks5SessionUDP *self = YUME_SOCKS5_SESSION_UDP (base);

    return self->data.task;
}

static void
yume_socks5_session_udp_set_task (YumeSocks5Session *base, YumeTask *task)
{
    YumeSocks5SessionUDP *self = YUME_SOCKS5_SESSION_UDP (base);

    self->data.task = task;
}

static YumeListNode *
yume_socks5_session_udp_get_node (YumeSocks5Session *base)
{
    YumeSocks5SessionUDP *self = YUME_SOCKS5_SESSION_UDP (base);

    return &self->data.node;
}

int
yume_socks5_session_udp_construct (YumeSocks5SessionUDP *self,
                                  struct udp_pcb *pcb, YumeTaskMutex *mutex)
{
    YumeRouteConfigServer *srv = yume_route_config_get_socks5_server ();
    YumeSocks5Addr addr;
    int type;
    int res;

    res = yume_socks5_addr_from_lwip (&addr, &pcb->local_ip, pcb->local_port);
    if (res < 0)
        return -1;

    if (yume_native_transport_is_direct_enabled ())
        type = YUME_SOCKS5_TYPE_UDP_IN_UDP;
    else if (srv->udp_in_udp)
        type = YUME_SOCKS5_TYPE_UDP_IN_UDP;
    else
        type = YUME_SOCKS5_TYPE_UDP_IN_TCP;

    res = yume_socks5_client_udp_construct (&self->base, type);
    if (res < 0)
        return -1;

    LOG_D ("%p Yume SOCKS5 session udp construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_SESSION_UDP_TYPE;

    udp_recv (pcb, udp_recv_handler, self);

    self->pcb = pcb;
    self->mutex = mutex;
    self->data.self = self;
    self->direct_addr_len = yume_socks5_addr_len (&addr);
    self->direct_addr = yume_malloc (self->direct_addr_len);
    if (!self->direct_addr)
        return -1;
    memcpy (self->direct_addr, &addr, self->direct_addr_len);
    self->direct_peer_fd = -1;
    self->direct_stream_handle = -1;

    return 0;
}

void
yume_socks5_session_udp_destruct (YumeObject *base)
{
    YumeSocks5SessionUDP *self = YUME_SOCKS5_SESSION_UDP (base);
    YumeListNode *node;

    LOG_D ("%p Yume SOCKS5 session udp destruct", self);

    udp_direct_stop (self, "udp session destruct");

    node = yume_list_first (&self->frame_list);
    while (node) {
        YumeSocks5UDPFrame *frame;

        frame = container_of (node, YumeSocks5UDPFrame, node);
        node = yume_list_node_next (node);
        pbuf_free (frame->data);
        yume_free (frame);
    }

    yume_task_mutex_lock (self->mutex);
    if (self->pcb) {
        udp_recv (self->pcb, NULL, NULL);
        udp_remove (self->pcb);
    }
    yume_task_mutex_unlock (self->mutex);

    if (self->direct_addr)
        yume_free (self->direct_addr);

    YUME_SOCKS5_CLIENT_UDP_TYPE->destruct (base);
}

static void *
yume_socks5_session_udp_iface (YumeObject *base, void *type)
{
    if (type == YUME_SOCKS5_SESSION_TYPE) {
        YumeSocks5SessionUDPClass *klass = YUME_OBJECT_GET_CLASS (base);
        return &klass->session;
    }

    return YUME_SOCKS5_CLIENT_UDP_TYPE->iface (base, type);
}

YumeObjectClass *
yume_socks5_session_udp_class (void)
{
    static YumeSocks5SessionUDPClass klass;
    YumeSocks5SessionUDPClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        YumeSocks5Class *skptr;
        YumeSocks5ClientClass *ckptr;
        YumeSocks5SessionIface *siptr;
        void *ptr;

        ptr = YUME_SOCKS5_CLIENT_UDP_TYPE;
        memcpy (kptr, ptr, sizeof (YumeSocks5ClientUDPClass));

        okptr->name = "YumeSocks5SessionUDP";
        okptr->destruct = yume_socks5_session_udp_destruct;
        okptr->iface = yume_socks5_session_udp_iface;

        skptr = YUME_SOCKS5_CLASS (kptr);
        skptr->binder = yume_socks5_session_udp_bind;

        ckptr = YUME_SOCKS5_CLIENT_CLASS (kptr);
        ckptr->set_upstream_addr = yume_socks5_session_udp_set_upstream_addr;

        siptr = &kptr->session;
        siptr->run = yume_socks5_session_udp_run;
        siptr->splicer = yume_socks5_session_udp_splice;
        siptr->get_task = yume_socks5_session_udp_get_task;
        siptr->set_task = yume_socks5_session_udp_set_task;
        siptr->get_node = yume_socks5_session_udp_get_node;
    }

    return okptr;
}
