/*
 ============================================================================
 Name        : yume-route-core.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2025 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume Route Backend
 ============================================================================
 */

#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>

#include <lwip/tcp.h>
#include <lwip/udp.h>
#include <lwip/nd6.h>
#include <lwip/netif.h>
#include <lwip/ip_addr.h>
#include <lwip/ip4_frag.h>
#include <lwip/ip6_frag.h>
#include <lwip/priv/tcp_priv.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-mutex.h>
#include <yume-task-system.h>
#include <yume-memory-allocator.h>

#include "yume-exec.h"
#include "yume-route-config.h"
#include "yume-logger.h"
#include "yume-tunnel.h"
#include "yume-compiler.h"
#include "yume-mapped-dns.h"
#include "yume-route-config-const.h"

#ifdef ANDROID
#include <android/log.h>
#define YUME_RC_LOG(...) __android_log_print(ANDROID_LOG_INFO, "yume-route-core", __VA_ARGS__)
#else
#define YUME_RC_LOG(...) do {} while (0)
#endif
#include "yume-socks5-session-tcp.h"
#include "yume-socks5-session-udp.h"
#include "yume-route-core.h"

static int run;
static int tun_fd = -1;
static int tun_fd_local;
static int session_count;
static int event_fds[2] = { -1, -1 };

static size_t stat_tx_packets;
static size_t stat_rx_packets;
static size_t stat_tx_bytes;
static size_t stat_rx_bytes;
static size_t android_udp_reject_count;

static struct netif netif;
static struct tcp_pcb *tcp;
static struct udp_pcb *udp;

static YumeTaskMutex mutex;
static YumeTask *task_event;
static YumeTask *task_lwip_io;
static YumeTask *task_lwip_timer;
static YumeList session_set;

static uint16_t
read_be16 (const unsigned char *p)
{
    return ((uint16_t)p[0] << 8) | p[1];
}

static void
format_ipv4 (const unsigned char *p, char *out, size_t out_len)
{
    snprintf (out, out_len, "%u.%u.%u.%u", p[0], p[1], p[2], p[3]);
}

static void
log_ipv4_packet (const char *direction, struct pbuf *p, size_t count)
{
    const unsigned char *data;
    unsigned int version;
    unsigned int ihl;
    unsigned int proto;
    char src[16];
    char dst[16];

    if (!p || !p->payload || p->len < 20)
        return;

    data = p->payload;
    version = data[0] >> 4;
    if (version != 4)
        return;

    ihl = (data[0] & 0x0F) * 4;
    if (ihl < 20 || p->len < ihl)
        return;

    proto = data[9];
    format_ipv4 (&data[12], src, sizeof (src));
    format_ipv4 (&data[16], dst, sizeof (dst));

    if (proto == 6 && p->len >= ihl + 20) {
        const unsigned char *tcp = data + ihl;
        uint8_t flags = tcp[13];

        YUME_RC_LOG ("%s #%zu TCP %s:%u -> %s:%u flags=0x%02x len=%u",
                     direction, count, src, read_be16 (&tcp[0]), dst,
                     read_be16 (&tcp[2]), flags, p->tot_len);
    } else if (proto == 17 && p->len >= ihl + 8) {
        const unsigned char *udp = data + ihl;

        YUME_RC_LOG ("%s #%zu UDP %s:%u -> %s:%u len=%u", direction, count,
                     src, read_be16 (&udp[0]), dst, read_be16 (&udp[2]),
                     p->tot_len);
    } else if (proto != 1) {
        YUME_RC_LOG ("%s #%zu IPv4 proto=%u %s -> %s len=%u", direction,
                     count, proto, src, dst, p->tot_len);
    }
}

#ifdef ANDROID
static int
should_drop_android_non_dns_udp (struct pbuf *p)
{
    const unsigned char *data;
    unsigned int version;
    unsigned int ihl;
    const unsigned char *udp;
    uint16_t dst_port;
    uint32_t dst_addr;

    if (!p || !p->payload || p->len < 28)
        return 0;

    data = p->payload;
    version = data[0] >> 4;
    if (version != 4)
        return 0;

    ihl = (data[0] & 0x0F) * 4;
    if (ihl < 20 || p->len < ihl + 8 || data[9] != 17)
        return 0;

    udp = data + ihl;
    dst_port = read_be16 (&udp[2]);
    memcpy (&dst_addr, &data[16], sizeof (dst_addr));
    if (dst_port == yume_route_config_get_mapdns_port () &&
        dst_addr == (uint32_t)yume_route_config_get_mapdns_address ()) {
        return 0;
    }

    android_udp_reject_count++;
    if (android_udp_reject_count <= 32 || (android_udp_reject_count % 128) == 0) {
        char src[16];
        char dst[16];

        format_ipv4 (&data[12], src, sizeof (src));
        format_ipv4 (&data[16], dst, sizeof (dst));
        YUME_RC_LOG ("lwip_io_task_entry: dropped non-DNS UDP %s:%u -> %s:%u before lwIP session creation",
                     src, read_be16 (&udp[0]), dst, dst_port);
    }

    return 1;
}
#endif

static const char *
lookup_mapped_host_for_ip (const ip_addr_t *ipaddr)
{
    YumeMappedDns *dns;

    if (!ipaddr || !IP_IS_V4 (ipaddr))
        return NULL;

    dns = yume_mapped_dns_get ();
    if (!dns)
        return NULL;

    return yume_mapped_dns_lookup (dns, ntohl (ip_2_ip4 (ipaddr)->addr));
}

static int
task_io_yielder (YumeTaskYieldType type, void *data)
{
    yume_task_yield (type);

    return run ? 0 : -1;
}

static err_t
netif_output_handler (struct netif *netif, struct pbuf *p)
{
    unsigned int mtu;
    ssize_t s;

    mtu = yume_route_config_get_tunnel_mtu ();
    if (mtu > 0 && p->tot_len > mtu) {
        YUME_RC_LOG ("netif_output_handler: outgoing packet %u exceeds configured mtu %u",
                     p->tot_len, mtu);
    }

    s = yume_tunnel_write (tun_fd, p);
    if (s <= 0) {
        if (errno == EAGAIN)
            return ERR_WOULDBLOCK;
        LOG_W ("Yume route backend write");
        return ERR_IF;
    }

    stat_rx_packets++;
    stat_rx_bytes += s;
    if (stat_rx_packets <= 64)
        log_ipv4_packet ("tun write", p, stat_rx_packets);
    if ((stat_rx_packets & 0x3F) == 0) {
        YUME_RC_LOG ("netif_output_handler: wrote %zu packets to tun so far (last=%zd bytes)",
                     stat_rx_packets, s);
    }

    return ERR_OK;
}

static err_t
netif_output_v4_handler (struct netif *netif, struct pbuf *p,
                         const ip4_addr_t *ipaddr)
{
    return netif_output_handler (netif, p);
}

static err_t
netif_output_v6_handler (struct netif *netif, struct pbuf *p,
                         const ip6_addr_t *ipaddr)
{
    return netif_output_handler (netif, p);
}

static err_t
netif_init_handler (struct netif *netif)
{
    unsigned int mtu = yume_route_config_get_tunnel_mtu ();

    if (mtu < 576)
        mtu = 576;
    if (mtu > 65535)
        mtu = 65535;

    netif->mtu = (u16_t)mtu;
#if LWIP_IPV6 && LWIP_ND6_ALLOW_RA_UPDATES
    netif->mtu6 = (u16_t)mtu;
#endif
    netif->output = netif_output_v4_handler;
    netif->output_ip6 = netif_output_v6_handler;

    YUME_RC_LOG ("netif_init_handler: route backend mtu=%u", mtu);

    return ERR_OK;
}

static void
yume_route_insert_session (YumeListNode *node)
{
    YumeSocks5SessionData *sd;
    int max_session_count;

    yume_list_add_tail (&session_set, node);
    session_count++;

    max_session_count = yume_route_config_get_misc_max_session_count ();
    if (!max_session_count || session_count < max_session_count)
        return;

    node = yume_list_first (&session_set);
    sd = container_of (node, YumeSocks5SessionData, node);
    yume_socks5_session_terminate (sd->self);
}

static void
yume_route_delete_session (YumeListNode *node)
{
    yume_list_del (&session_set, node);
    session_count--;
}

void
yume_route_update_session (YumeListNode *node)
{
    int max_session_count;

    max_session_count = yume_route_config_get_misc_max_session_count ();
    if (!max_session_count)
        return;

    yume_list_del (&session_set, node);
    yume_list_add_tail (&session_set, node);
}

static void
yume_socks5_session_task_entry (void *data)
{
    YumeSocks5Session *s = data;

    yume_socks5_session_run (s);

    yume_route_delete_session (yume_socks5_session_get_node (s));
    yume_object_unref (YUME_OBJECT (s));
}

static err_t
tcp_accept_handler (void *arg, struct tcp_pcb *pcb, err_t err)
{
    YumeSocks5SessionTCP *tcp;
    YumeListNode *node;
    int stack_size;
    YumeTask *task;

    if (err != ERR_OK)
        return err;

    if (!run)
        return ERR_RST;

    {
        char dest[IPADDR_STRLEN_MAX];
        const char *mapped_name = lookup_mapped_host_for_ip (&pcb->local_ip);

        ipaddr_ntoa_r (&pcb->local_ip, dest, sizeof (dest));
        if (mapped_name) {
            YUME_RC_LOG ("tcp_accept_handler: lwip accepted TCP SYN -> %s:%u mapped=%s",
                         dest, pcb->local_port, mapped_name);
        } else {
            YUME_RC_LOG ("tcp_accept_handler: lwip accepted TCP SYN -> %s:%u",
                         dest, pcb->local_port);
        }
    }

    tcp = yume_socks5_session_tcp_new (pcb, &mutex);
    if (!tcp)
        return ERR_MEM;

    stack_size = yume_route_config_get_misc_task_stack_size ();
    task = yume_task_new (stack_size);
    if (!task) {
        yume_object_unref (YUME_OBJECT (tcp));
        return ERR_MEM;
    }

    yume_socks5_session_set_task (YUME_SOCKS5_SESSION (tcp), task);
    node = yume_socks5_session_get_node (YUME_SOCKS5_SESSION (tcp));
    yume_route_insert_session (node);
    yume_task_run (task, yume_socks5_session_task_entry, tcp);
    yume_task_wakeup (task_lwip_timer);

    return ERR_OK;
}

static void
dns_recv_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                  const ip_addr_t *addr, u16_t port)
{
    YumeMappedDns *dns = arg;
    struct pbuf *b;
    int res;

    LOG_D ("%p mapped dns handle", dns);

    b = pbuf_alloc (PBUF_TRANSPORT, UDP_BUF_SIZE, PBUF_RAM);
    if (!b)
        goto exit;

    res = yume_mapped_dns_handle (dns, p->payload, p->len, b->payload, b->len);
    if (res < 0)
        goto free;

    b->len = res;
    b->tot_len = res;
    YUME_RC_LOG ("dns_recv_handler: answered mapped DNS query from local %u with %d bytes",
                 pcb->local_port, res);
    udp_sendfrom (pcb, b, &pcb->local_ip, pcb->local_port);

free:
    pbuf_free (b);
exit:
    pbuf_free (p);
    udp_recv (pcb, NULL, NULL);
    udp_remove (pcb);
}

static void
udp_recv_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                  const ip_addr_t *addr, u16_t port)
{
    YumeSocks5SessionUDP *udp;
    YumeListNode *node;
    YumeMappedDns *dns;
    int stack_size;
    YumeTask *task;

    if (!run) {
        if (p)
            pbuf_free (p);
        udp_remove (pcb);
        return;
    }

    dns = yume_mapped_dns_get ();
    if (dns && addr->type == IPADDR_TYPE_V4) {
        int faddr = yume_route_config_get_mapdns_address ();
        int fport = yume_route_config_get_mapdns_port ();
        if (fport == port && faddr == ip_2_ip4 (addr)->addr) {
            char dest[IPADDR_STRLEN_MAX];

            ipaddr_ntoa_r (addr, dest, sizeof (dest));
            YUME_RC_LOG ("udp_recv_handler: routed DNS query to mapped DNS %s:%u",
                         dest, port);
            udp_recv (pcb, dns_recv_handler, dns);
            return;
        }
    }

    udp = yume_socks5_session_udp_new (pcb, &mutex);
    if (!udp) {
        udp_remove (pcb);
        return;
    }

    stack_size = yume_route_config_get_misc_task_stack_size ();
    task = yume_task_new (stack_size);
    if (!task) {
        yume_object_unref (YUME_OBJECT (udp));
        return;
    }

    yume_socks5_session_set_task (YUME_SOCKS5_SESSION (udp), task);
    node = yume_socks5_session_get_node (YUME_SOCKS5_SESSION (udp));
    yume_route_insert_session (node);
    yume_task_run (task, yume_socks5_session_task_entry, udp);
    yume_task_wakeup (task_lwip_timer);
}

static void
event_task_entry (void *data)
{
    YumeListNode *node;
    int val;

    LOG_D ("Yume route backend event task run");

    yume_task_add_fd (task_event, event_fds[0], POLLIN);

    yume_task_io_read (event_fds[0], &val, sizeof (val), NULL, NULL);

    run = 0;
    node = yume_list_first (&session_set);
    while (node) {
        YumeSocks5SessionData *sd;
        YumeListNode *next;

        next = yume_list_node_next (node);
        sd = container_of (node, YumeSocks5SessionData, node);
        yume_socks5_session_terminate (sd->self);
        node = next;
    }

    if (tun_fd >= 0 && task_lwip_io) {
        yume_tunnel_del_task (tun_fd, task_lwip_io);
        yume_task_wakeup (task_lwip_io);
    }
    if (task_lwip_timer)
        yume_task_wakeup (task_lwip_timer);

    yume_task_join (task_lwip_io);
    yume_task_join (task_lwip_timer);
    yume_task_del_fd (task_event, event_fds[0]);
}

static void
lwip_io_task_entry (void *data)
{
    const unsigned int mtu = yume_route_config_get_tunnel_mtu ();

    LOG_D ("Yume route backend lwip task run");

    yume_tunnel_add_task (tun_fd, task_lwip_io);

    YUME_RC_LOG ("lwip_io_task_entry: starting tun read loop fd=%d mtu=%u", tun_fd, mtu);

    size_t local_pkts = 0;
    for (; run;) {
        struct pbuf *buf;

        buf = yume_tunnel_read (tun_fd, mtu, task_io_yielder, NULL);
        if (!buf)
            continue;

        stat_tx_packets++;
        stat_tx_bytes += buf->tot_len;
        if (stat_tx_packets <= 64)
            log_ipv4_packet ("tun read", buf, stat_tx_packets);
        if ((++local_pkts & 0x3F) == 0) {
            YUME_RC_LOG ("lwip_io_task_entry: read %zu packets so far (last=%u bytes)",
                         local_pkts, buf->tot_len);
        }

#ifdef ANDROID
        if (should_drop_android_non_dns_udp (buf)) {
            pbuf_free (buf);
            continue;
        }
#endif

        yume_task_mutex_lock (&mutex);
        if (netif.input (buf, &netif) != ERR_OK)
            pbuf_free (buf);
        yume_task_mutex_unlock (&mutex);
    }

    YUME_RC_LOG ("lwip_io_task_entry: exiting after %zu packets", local_pkts);

    yume_tunnel_del_task (tun_fd, task_lwip_io);
}

static void
lwip_timer_task_entry (void *data)
{
    unsigned int i;

    LOG_D ("Yume route backend timer task run");

    for (i = 1; run; i++) {
        yume_task_mutex_lock (&mutex);
        tcp_tmr ();

        if ((i & 3) == 0) {
#if IP_REASSEMBLY
            ip_reass_tmr ();
#endif
#if LWIP_IPV6
            nd6_tmr ();
#if LWIP_IPV6_REASS
            ip6_reass_tmr ();
#endif
#endif
        }
        yume_task_mutex_unlock (&mutex);

        if (yume_list_first (&session_set))
            yume_task_sleep (TCP_TMR_INTERVAL);
        else
            yume_task_yield (YUME_TASK_WAITIO);
    }
}

static int
tunnel_init (int extern_tun_fd)
{
    const char *script_path, *name, *ipv4, *ipv6;
    int multi_queue, res;
    unsigned int mtu;

    if (extern_tun_fd >= 0) {
        int nonblock = 1;

        res = ioctl (extern_tun_fd, FIONBIO, (char *)&nonblock);
        if (res < 0) {
            LOG_E ("Yume route backend non-blocking");
            return -1;
        }

        tun_fd = extern_tun_fd;
        return 0;
    }

    name = yume_route_config_get_tunnel_name ();
    multi_queue = yume_route_config_get_tunnel_multi_queue ();
    tun_fd = yume_tunnel_open (name, multi_queue);
    if (tun_fd < 0) {
        LOG_E ("Yume route backend open (%s)", strerror (errno));
        return -1;
    }

    mtu = yume_route_config_get_tunnel_mtu ();
    res = yume_tunnel_set_mtu (mtu);
    if (res < 0) {
        LOG_E ("Yume route backend mtu");
        return -1;
    }

    ipv4 = yume_route_config_get_tunnel_ipv4_address ();
    if (ipv4) {
        res = yume_tunnel_set_ipv4 (ipv4, 32);
        if (res < 0) {
            LOG_E ("Yume route backend ipv4");
            return -1;
        }
    }

    ipv6 = yume_route_config_get_tunnel_ipv6_address ();
    if (ipv6) {
        res = yume_tunnel_set_ipv6 (ipv6, 128);
        if (res < 0) {
            LOG_E ("Yume route backend ipv6");
            return -1;
        }
    }

    res = yume_tunnel_set_state (1);
    if (res < 0) {
        LOG_E ("Yume route backend state");
        return -1;
    }

    script_path = yume_route_config_get_tunnel_post_up_script ();
    if (script_path)
        yume_exec_run (script_path, yume_tunnel_get_name (),
                      yume_tunnel_get_index (), 0);

    tun_fd_local = 1;
    return 0;
}

static void
tunnel_fini (void)
{
    const char *script_path;

    if (!tun_fd_local)
        return;

    script_path = yume_route_config_get_tunnel_pre_down_script ();
    if (script_path)
        yume_exec_run (script_path, yume_tunnel_get_name (),
                      yume_tunnel_get_index (), 1);

    yume_tunnel_close (tun_fd);
    tun_fd_local = 0;
    tun_fd = -1;
}

static int
gateway_init (void)
{
    ip4_addr_t addr4, mask, gw;
    ip6_addr_t addr6;

    netif_add_noaddr (&netif, NULL, netif_init_handler, ip_input);

    ip4_addr_set_loopback (&addr4);
    ip4_addr_set_any (&mask);
    ip4_addr_set_any (&gw);
    netif_set_addr (&netif, &addr4, &mask, &gw);

    ip6_addr_set_loopback (&addr6);
    netif_add_ip6_address (&netif, &addr6, NULL);

    netif_set_up (&netif);
    netif_set_link_up (&netif);
    netif_set_default (&netif);
    netif_set_flags (&netif, NETIF_FLAG_PRETEND_TCP);

    tcp = tcp_new_ip_type (IPADDR_TYPE_ANY);
    tcp_bind_netif (tcp, &netif);
    tcp_bind (tcp, NULL, 0);
    tcp = tcp_listen (tcp);
    tcp_accept (tcp, tcp_accept_handler);

    udp = udp_new_ip_type (IPADDR_TYPE_ANY);
    udp_bind_netif (udp, &netif);
    udp_bind (udp, NULL, 0);
    udp_recv (udp, udp_recv_handler, NULL);

    return 0;
}

static void
gateway_fini (void)
{
    udp_remove (udp);
    tcp_close (tcp);
    netif_remove (&netif);
}

static int
event_task_init (void)
{
    int nonblock = 1;
    int res;

    res = socketpair (PF_LOCAL, SOCK_STREAM, 0, event_fds);
    if (res < 0) {
        LOG_E ("Yume route backend event");
        return -1;
    }

    res = ioctl (event_fds[0], FIONBIO, (char *)&nonblock);
    if (res < 0) {
        LOG_E ("Yume route backend event nonblock");
        return -1;
    }

    task_event = yume_task_new (-1);
    if (!task_event) {
        LOG_E ("Yume route backend task event");
        return -1;
    }

    return 0;
}

static void
event_task_fini (void)
{
    if (task_event) {
        yume_task_unref (task_event);
        task_event = NULL;
    }

    if (event_fds[0] >= 0) {
        close (event_fds[0]);
        event_fds[0] = -1;
    }
    if (event_fds[1] >= 0) {
        close (event_fds[1]);
        event_fds[1] = -1;
    }
}

static int
lwip_io_task_init (void)
{
    task_lwip_io = yume_task_new (-1);
    if (!task_lwip_io) {
        LOG_E ("Yume route backend task lwip");
        return -1;
    }
    yume_task_set_priority (task_lwip_io, 1);

    return 0;
}

static void
lwip_io_task_fini (void)
{
    if (task_lwip_io) {
        yume_task_unref (task_lwip_io);
        task_lwip_io = NULL;
    }
}

static int
lwip_timer_task_init (void)
{
    task_lwip_timer = yume_task_new (-1);
    if (!task_lwip_timer) {
        LOG_E ("Yume route backend task timer");
        return -1;
    }
    yume_task_set_priority (task_lwip_timer, 1);

    return 0;
}

static void
lwip_timer_task_fini (void)
{
    if (task_lwip_timer) {
        yume_task_unref (task_lwip_timer);
        task_lwip_timer = NULL;
    }
}

static int
mapped_dns_init (void)
{
    YumeMappedDns *dns;
    int cache_size;
    int network;
    int netmask;

    network = yume_route_config_get_mapdns_network ();
    netmask = yume_route_config_get_mapdns_netmask ();
    cache_size = yume_route_config_get_mapdns_cache_size ();

    if (!cache_size)
        return 0;

    dns = yume_mapped_dns_new (network, netmask, cache_size);
    if (!dns)
        return -1;

    yume_mapped_dns_put (dns);

    return 0;
}

static void
mapped_dns_fini (void)
{
    YumeMappedDns *dns;

    dns = yume_mapped_dns_get ();
    if (dns) {
        yume_object_unref (YUME_OBJECT (dns));
        yume_mapped_dns_put (NULL);
    }
}

int
yume_route_init (int tun_fd)
{
    int res;

    LOG_D ("Yume route backend init");

    res = tunnel_init (tun_fd);
    if (res < 0)
        goto exit;

    res = gateway_init ();
    if (res < 0)
        goto exit;

    res = event_task_init ();
    if (res < 0)
        goto exit;

    res = lwip_io_task_init ();
    if (res < 0)
        goto exit;

    res = lwip_timer_task_init ();
    if (res < 0)
        goto exit;

    res = mapped_dns_init ();
    if (res < 0)
        goto exit;

    signal (SIGPIPE, SIG_IGN);

    yume_task_mutex_init (&mutex);

    return 0;

exit:
    yume_route_fini ();
    return -1;
}

void
yume_route_fini (void)
{
    LOG_D ("Yume route backend fini");

    mapped_dns_fini ();
    lwip_timer_task_fini ();
    lwip_io_task_fini ();
    event_task_fini ();
    gateway_fini ();
    tunnel_fini ();

    stat_tx_packets = 0;
    stat_rx_packets = 0;
    stat_tx_bytes = 0;
    stat_rx_bytes = 0;
    android_udp_reject_count = 0;
}

int
yume_route_run (void)
{
    LOG_D ("Yume route backend run");

    /* Mark the backend active before scheduling worker tasks so their run
     * loops do not observe a false stopped state and exit immediately. */
    run = 1;

    task_event = yume_task_ref (task_event);
    yume_task_run (task_event, event_task_entry, NULL);

    task_lwip_io = yume_task_ref (task_lwip_io);
    yume_task_run (task_lwip_io, lwip_io_task_entry, NULL);

    task_lwip_timer = yume_task_ref (task_lwip_timer);
    yume_task_run (task_lwip_timer, lwip_timer_task_entry, NULL);
    yume_task_system_run ();

    return 0;
}

void
yume_route_request_stop (void)
{
    int res;
    int fd;
    int attempt;
    int signal_value = 1;

    LOG_D ("Yume route backend stop");
    run = 0;

    for (attempt = 0; attempt < 50; attempt++) {
        fd = READ_ONCE (event_fds[1]);
        if (fd >= 0)
            break;
        /* Wait for async initialization */
        usleep (20 * 1000);
    }

    if (fd < 0) {
        LOG_W ("Yume route backend stop requested before the event channel was ready");
        return;
    }

    res = write (fd, &signal_value, sizeof (signal_value));
    if (res <= 0 && errno != EPIPE && errno != EBADF)
        LOG_W ("Yume route backend write event");
}

void
yume_route_stats (size_t *tx_packets, size_t *tx_bytes,
                         size_t *rx_packets, size_t *rx_bytes)
{
    LOG_D ("Yume route backend stats");

    if (tx_packets)
        *tx_packets = stat_tx_packets;

    if (tx_bytes)
        *tx_bytes = stat_tx_bytes;

    if (rx_packets)
        *rx_packets = stat_rx_packets;

    if (rx_bytes)
        *rx_bytes = stat_rx_bytes;
}
