/*
 ============================================================================
 Name        : yume-socks5-session.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume SOCKS5 Session
 ============================================================================
 */

#ifndef __YUME_SOCKS5_SESSION_H__
#define __YUME_SOCKS5_SESSION_H__

#include <yume-task.h>

#include "yume-list.h"

#define YUME_SOCKS5_SESSION(p) ((YumeSocks5Session *)p)
#define YUME_SOCKS5_SESSION_IFACE(p) ((YumeSocks5SessionIface *)p)
#define YUME_SOCKS5_SESSION_TYPE (yume_socks5_session_iface ())

typedef void YumeSocks5Session;
typedef struct _YumeSocks5SessionData YumeSocks5SessionData;
typedef struct _YumeSocks5SessionIface YumeSocks5SessionIface;

struct _YumeSocks5SessionData
{
    YumeListNode node;
    YumeTask *task;
    YumeSocks5Session *self;
};

struct _YumeSocks5SessionIface
{
    void (*run) (YumeSocks5Session *self);
    void (*splicer) (YumeSocks5Session *self);
    YumeTask *(*get_task) (YumeSocks5Session *self);
    void (*set_task) (YumeSocks5Session *self, YumeTask *task);
    YumeListNode *(*get_node) (YumeSocks5Session *self);
};

void *yume_socks5_session_iface (void);

void yume_socks5_session_run (YumeSocks5Session *self);
void yume_socks5_session_run_via_socks (YumeSocks5Session *self);
void yume_socks5_session_terminate (YumeSocks5Session *self);

void yume_socks5_session_set_task (YumeSocks5Session *self, YumeTask *task);
YumeListNode *yume_socks5_session_get_node (YumeSocks5Session *self);

#endif /* __YUME_SOCKS5_SESSION_H__ */
