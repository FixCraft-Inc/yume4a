/*
 ============================================================================
 Name        : yume-socks5-misc-priv.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 Misc Private
 ============================================================================
 */

#ifndef __YUME_SOCKS5_MISC_PRIV_H__
#define __YUME_SOCKS5_MISC_PRIV_H__

#include <netinet/in.h>

#include <yume-task.h>
#include <yume-task-io.h>

#include "yume-socks5-misc.h"
#include "yume-socks5-proto.h"

#ifdef __cplusplus
extern "C" {
#endif

int yume_socks5_socket (int type);

const char *yume_socks5_addr_into_str (const YumeSocks5Addr *addr, char *buf,
                                      int len);

int yume_socks5_get_connect_timeout (void);
int yume_socks5_get_tcp_timeout (void);
int yume_socks5_get_udp_timeout (void);

int yume_socks5_get_task_stack_size (void);
int yume_socks5_get_udp_copy_buffer_nums (void);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_MISC_PRIV_H__ */
