/*
 ============================================================================
 Name        : yume-tunnel-windows.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
 Description : Tunnel on Windows
 ============================================================================
 */

#ifndef __YUME_TUNNEL_WINDOWS_H__
#define __YUME_TUNNEL_WINDOWS_H__

struct pbuf *yume_tunnel_read (int fd, int mtu, YumeTaskIOYielder yielder,
                              void *yielder_data);
ssize_t yume_tunnel_write (int fd, struct pbuf *buf);

#endif /* __YUME_TUNNEL_WINDOWS_H__ */
