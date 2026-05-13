/*
 ============================================================================
 Name        : yume-mapped-dns.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Mapped DNS
 ============================================================================
 */

#ifndef __YUME_MAPPED_DNS_H__
#define __YUME_MAPPED_DNS_H__

#include <pthread.h>

#include <yume-list.h>
#include <yume-rbtree.h>
#include <yume-object.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_MAPPED_DNS(p) ((YumeMappedDns *)p)
#define YUME_MAPPED_DNS_CLASS(p) ((YumeMappedDnsClass *)p)
#define YUME_MAPPED_DNS_TYPE (yume_mapped_dns_class ())

typedef struct _YumeMappedDns YumeMappedDns;
typedef struct _YumeMappedDnsClass YumeMappedDnsClass;
typedef struct _YumeMappedDnsNode YumeMappedDnsNode;

struct _YumeMappedDns
{
    YumeObject base;

    int use;
    int max;
    int net;
    int mask;

    pthread_mutex_t mutex;
    YumeList list;
    YumeRBTree tree;
    YumeMappedDnsNode *records[0];
};

struct _YumeMappedDnsClass
{
    YumeObjectClass base;
};

YumeObjectClass *yume_mapped_dns_class (void);

int yume_mapped_dns_construct (YumeMappedDns *self, int net, int mask, int max);

YumeMappedDns *yume_mapped_dns_new (int net, int mask, int max);

YumeMappedDns *yume_mapped_dns_get (void);
void yume_mapped_dns_put (YumeMappedDns *self);

int yume_mapped_dns_handle (YumeMappedDns *self, void *req, int qlen, void *res,
                           int slen);
const char *yume_mapped_dns_lookup (YumeMappedDns *self, int ip);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_MAPPED_DNS_H__ */
