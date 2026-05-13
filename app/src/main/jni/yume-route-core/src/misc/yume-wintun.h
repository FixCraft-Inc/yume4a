/*
 ============================================================================
 Name        : yume-wintun.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
 Description : Wintun utils
 ============================================================================
 */

#ifndef __YUME_WINTUN_H__
#define __YUME_WINTUN_H__

#define YUME_WINTUN_EAGAIN (259)

typedef void YumeWinTun;
typedef void YumeWinTunAdapter;
typedef void YumeWinTunSession;

YumeWinTun *yume_wintun_open (void);
void yume_wintun_close (YumeWinTun *self);

YumeWinTunAdapter *yume_wintun_adapter_create (const char *name);
void yume_wintun_adapter_close (YumeWinTunAdapter *adapter);

int yume_wintun_adapter_get_index (YumeWinTunAdapter *adapter);
int yume_wintun_adapter_set_mtu (YumeWinTunAdapter *adapter, int mtu);
int yume_wintun_adapter_set_ipv4 (YumeWinTunAdapter *adapter, char addr[4],
                                 unsigned int prefix);
int yume_wintun_adapter_set_ipv6 (YumeWinTunAdapter *adapter, char addr[16],
                                 unsigned int prefix);

YumeWinTunSession *yume_wintun_session_start (YumeWinTunAdapter *adapter);
void yume_wintun_session_stop (YumeWinTunSession *session);

void *yume_wintun_session_get_read_wait_event (YumeWinTunSession *session);

void *yume_wintun_session_receive (YumeWinTunSession *session, int *size);
void yume_wintun_session_release (YumeWinTunSession *session, void *packet);

void *yume_wintun_session_allocate (YumeWinTunSession *session, int size);
void yume_wintun_session_send (YumeWinTunSession *session, void *packet);

unsigned int yume_wintun_get_last_error (void);

#endif /* __YUME_WINTUN_H__ */
