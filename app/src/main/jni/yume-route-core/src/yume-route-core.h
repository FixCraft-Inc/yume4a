/*
 ============================================================================
 Name        : yume-route-core.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume Route Backend
 ============================================================================
 */

#ifndef __YUME_ROUTE_H__
#define __YUME_ROUTE_H__

#include "yume-list.h"

int yume_route_init (int tun_fd);
void yume_route_fini (void);

int yume_route_run (void);
void yume_route_request_stop (void);

void yume_route_stats (size_t *tx_packets, size_t *tx_bytes,
                              size_t *rx_packets, size_t *rx_bytes);

void yume_route_update_session (YumeListNode *node);

#endif /* __YUME_ROUTE_H__ */
