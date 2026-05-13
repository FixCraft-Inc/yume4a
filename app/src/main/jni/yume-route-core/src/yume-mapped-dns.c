/*
 ============================================================================
 Name        : yume-mapped-dns.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Mapped DNS
 ============================================================================
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>

#include <yume-compiler.h>
#include <yume-memory-allocator.h>

#include "yume-logger.h"

#include "yume-mapped-dns.h"

#define YUME_MAPPED_DNS_HOST_OFFSET 16

static YumeMappedDns *singleton;

typedef struct _DNSHdr DNSHdr;

struct _DNSHdr
{
    uint16_t id;
    uint16_t fl;
    uint16_t qd;
    uint16_t an;
    uint16_t ns;
    uint16_t ar;
};

struct _YumeMappedDnsNode
{
    YumeRBTreeNode tree;
    YumeListNode list;
    char *name;
    int idx;
};

YumeMappedDns *
yume_mapped_dns_new (int net, int mask, int max)
{
    YumeMappedDns *self;
    int res;

    self = yume_malloc0 (sizeof (YumeMappedDns) + sizeof (void *) * max);
    if (!self)
        return NULL;

    res = yume_mapped_dns_construct (self, net, mask, max);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p mapped dns new", self);

    return self;
}

YumeMappedDns *
yume_mapped_dns_get (void)
{
    return singleton;
}

void
yume_mapped_dns_put (YumeMappedDns *self)
{
    singleton = self;
}

static YumeMappedDnsNode *
yume_mapped_dns_node_alloc (const char *name)
{
    YumeMappedDnsNode *node;

    node = yume_malloc0 (sizeof (YumeMappedDnsNode));
    if (!node)
        return NULL;

    node->name = strdup (name);

    return node;
}

static void
yume_mapped_dns_node_free (YumeMappedDnsNode *node)
{
    free (node->name);
    yume_free (node);
}

static int
yume_mapped_dns_find (YumeMappedDns *self, const char *name)
{
    YumeRBTreeNode **new = &self->tree.root, *parent = NULL;
    YumeMappedDnsNode *node;
    int idx = self->use;

    pthread_mutex_lock (&self->mutex);

    while (*new) {
        int res;

        node = container_of (*new, YumeMappedDnsNode, tree);
        res = strcmp (node->name, name);
        parent = *new;

        if (res < 0) {
            new = &((*new)->left);
        } else if (res > 0) {
            new = &((*new)->right);
        } else {
            yume_list_del (&self->list, &node->list);
            yume_list_add_tail (&self->list, &node->list);
            pthread_mutex_unlock (&self->mutex);
            return node->idx;
        }
    }

    node = yume_mapped_dns_node_alloc (name);
    if (!node) {
        pthread_mutex_unlock (&self->mutex);
        return -1;
    }

    yume_rbtree_node_link (&node->tree, parent, new);
    yume_rbtree_insert_color (&self->tree, &node->tree);
    yume_list_add_tail (&self->list, &node->list);

    if (self->use < self->max) {
        self->use++;
    } else {
        YumeMappedDnsNode *nf;
        YumeListNode *nl;

        nl = yume_list_first (&self->list);
        nf = container_of (nl, YumeMappedDnsNode, list);

        idx = nf->idx;
        yume_rbtree_erase (&self->tree, &nf->tree);
        yume_list_del (&self->list, &nf->list);
        yume_mapped_dns_node_free (nf);
    }

    self->records[idx] = node;
    node->idx = idx;

    pthread_mutex_unlock (&self->mutex);
    return node->idx;
}

static inline uint16_t
read_u16 (const uint8_t *p)
{
    return ((uint16_t)p[0] << 8) | p[1];
}

static inline void
write_u16 (uint8_t *p, uint16_t v)
{
    p[0] = v >> 8;
    p[1] = v;
}

static inline void
write_u32 (uint8_t *p, uint32_t v)
{
    p[0] = v >> 24;
    p[1] = v >> 16;
    p[2] = v >> 8;
    p[3] = v;
}

int
yume_mapped_dns_handle (YumeMappedDns *self, void *req, int qlen, void *res,
                       int slen)
{
    DNSHdr *qhdr = req;
    DNSHdr *shdr = res;
    uint8_t *rb = req;
    uint8_t *sb = res;
    int ips[32];
    int ipo[32];
    int ipn = 0;
    int off;
    int i;

    if (slen < qlen)
        return -1;

    memcpy (res, req, qlen);
    qhdr->qd = ntohs (qhdr->qd);
    shdr->fl = ntohs (shdr->fl);
    shdr->ns = 0;
    shdr->an = 0;
    shdr->ar = 0;

    if (qhdr->qd > 32)
        return -1;

    off = sizeof (DNSHdr);
    for (i = 0; i < qhdr->qd; i++) {
        ipo[ipn] = off;

        while (rb[off]) {
            int poff = off;

            off += 1 + rb[off];
            if (off >= qlen)
                return -1;

            rb[poff] = '.';
        }

        off++;
        if ((off + 3) >= qlen)
            return -1;

        if ((read_u16 (&rb[off + 0]) == 1) && (read_u16 (&rb[off + 2]) == 1)) {
            int idx;

            idx = yume_mapped_dns_find (self, (char *)&rb[ipo[ipn] + 1]);
            if (idx >= 0) {
                ips[ipn] = self->net | (idx + YUME_MAPPED_DNS_HOST_OFFSET);
                ipn++;
            }
        }

        off += 4;
    }

    for (i = 0; i < ipn; i++) {
        if ((off + 15) >= slen)
            return -1;

        sb[off + 0] = 0xc0;
        sb[off + 1] = ipo[i];
        write_u16 (&sb[off + 2], 1);
        write_u16 (&sb[off + 4], 1);
        write_u32 (&sb[off + 6], 1);
        write_u16 (&sb[off + 10], 4);
        write_u32 (&sb[off + 12], ips[i]);

        off += 16;
    }

    shdr->fl = htons (shdr->fl | 0x8000 | ((shdr->fl & 0x100) >> 1));
    shdr->an = htons (ipn);

    return off;
}

const char *
yume_mapped_dns_lookup (YumeMappedDns *self, int ip)
{
    YumeMappedDnsNode *node;
    int idx;

    pthread_mutex_lock (&self->mutex);

    idx = (ip & ~self->mask) - YUME_MAPPED_DNS_HOST_OFFSET;
    if (idx < 0 || idx >= self->max) {
        pthread_mutex_unlock (&self->mutex);
        return NULL;
    }

    node = self->records[idx];
    if (!node) {
        pthread_mutex_unlock (&self->mutex);
        return NULL;
    }

    yume_list_del (&self->list, &node->list);
    yume_list_add_tail (&self->list, &node->list);

    pthread_mutex_unlock (&self->mutex);
    return node->name;
}

int
yume_mapped_dns_construct (YumeMappedDns *self, int net, int mask, int max)
{
    int res;

    res = yume_object_construct (&self->base);
    if (res < 0)
        return res;

    LOG_D ("%p mapped dns construct", self);

    YUME_OBJECT (self)->klass = YUME_MAPPED_DNS_TYPE;

    if ((~mask) <= YUME_MAPPED_DNS_HOST_OFFSET ||
        max > ((~mask) - YUME_MAPPED_DNS_HOST_OFFSET))
        return -1;

    self->max = max;
    self->net = net;
    self->mask = mask;
    pthread_mutex_init (&self->mutex, NULL);

    return 0;
}

static void
yume_mapped_dns_destruct (YumeObject *base)
{
    YumeMappedDns *self = YUME_MAPPED_DNS (base);
    YumeListNode *n;

    LOG_D ("%p mapped dns destruct", self);

    pthread_mutex_destroy (&self->mutex);

    n = yume_list_first (&self->list);
    while (n) {
        YumeMappedDnsNode *t;

        t = container_of (n, YumeMappedDnsNode, list);
        n = yume_list_node_next (n);
        yume_mapped_dns_node_free (t);
    }

    YUME_OBJECT_TYPE->destruct (base);
    yume_free (base);
}

YumeObjectClass *
yume_mapped_dns_class (void)
{
    static YumeMappedDnsClass klass;
    YumeMappedDnsClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        memcpy (kptr, YUME_OBJECT_TYPE, sizeof (YumeObjectClass));

        okptr->name = "YumeMappedDns";
        okptr->destruct = yume_mapped_dns_destruct;
    }

    return okptr;
}
