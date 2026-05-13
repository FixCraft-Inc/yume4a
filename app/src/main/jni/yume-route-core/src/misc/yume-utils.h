/*
 ============================================================================
 Name        : yume-utils.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Utils
 ============================================================================
 */

#ifndef __YUME_UTILS_H__
#define __YUME_UTILS_H__

#include <lwip/ip_addr.h>
#include <yume-socks5-proto.h>

void run_as_daemon (const char *pid_file);
int set_limit_nofile (int limit_nofile);
int set_sock_mark (int fd, unsigned int mark);

int yume_socks5_addr_from_lwip (YumeSocks5Addr *addr, const ip_addr_t *ip,
                               u16_t port);
int yume_socks5_addr_into_lwip (const YumeSocks5Addr *addr, ip_addr_t *ip,
                               u16_t *port);

#endif /* __YUME_UTILS_H__ */
