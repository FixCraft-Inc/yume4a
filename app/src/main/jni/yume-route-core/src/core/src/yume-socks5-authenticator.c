/*
 ============================================================================
 Name        : yume-socks5-authenticator.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
 Description : Socks5 Authenticator
 ============================================================================
 */

#include <stdlib.h>
#include <string.h>

#include "yume-compiler.h"
#include "yume-socks5-logger-priv.h"

#include "yume-socks5-authenticator.h"

YumeSocks5Authenticator *
yume_socks5_authenticator_new (void)
{
    YumeSocks5Authenticator *self;
    int res;

    self = calloc (1, sizeof (YumeSocks5Authenticator));
    if (!self)
        return NULL;

    res = yume_socks5_authenticator_construct (self);
    if (res < 0) {
        free (self);
        return NULL;
    }

    LOG_D ("%p socks5 authenticator new", self);

    return self;
}

int
yume_socks5_authenticator_add (YumeSocks5Authenticator *self, YumeSocks5User *user)
{
    YumeRBTreeNode **new = &self->tree.root, *parent = NULL;

    while (*new) {
        YumeSocks5User *this;
        int res;

        this = container_of (*new, YumeSocks5User, node);

        if (this->name_len < user->name_len)
            res = -1;
        else if (this->name_len > user->name_len)
            res = 1;
        else
            res = memcmp (this->name, user->name, this->name_len);

        parent = *new;
        if (res < 0)
            new = &((*new)->left);
        else if (res > 0)
            new = &((*new)->right);
        else
            return -1;
    }

    yume_rbtree_node_link (&user->node, parent, new);
    yume_rbtree_insert_color (&self->tree, &user->node);

    return 0;
}

int
yume_socks5_authenticator_del (YumeSocks5Authenticator *self, const char *name,
                              unsigned int name_len)
{
    YumeRBTreeNode *node = self->tree.root;

    while (node) {
        YumeSocks5User *this;
        int res;

        this = container_of (node, YumeSocks5User, node);
        if (this->name_len < name_len)
            res = -1;
        else if (this->name_len > name_len)
            res = 1;
        else
            res = memcmp (this->name, name, name_len);

        if (res < 0) {
            node = node->left;
        } else if (res > 0) {
            node = node->right;
        } else {
            yume_rbtree_erase (&self->tree, node);
            yume_object_unref (YUME_OBJECT (this));
            return 0;
        }
    }

    return -1;
}

YumeSocks5User *
yume_socks5_authenticator_get (YumeSocks5Authenticator *self, const char *name,
                              unsigned int name_len)
{
    YumeRBTreeNode *node = self->tree.root;

    while (node) {
        YumeSocks5User *this;
        int res;

        this = container_of (node, YumeSocks5User, node);
        if (this->name_len < name_len)
            res = -1;
        else if (this->name_len > name_len)
            res = 1;
        else
            res = memcmp (this->name, name, name_len);

        if (res < 0)
            node = node->left;
        else if (res > 0)
            node = node->right;
        else
            return this;
    }

    return NULL;
}

void
yume_socks5_authenticator_clear (YumeSocks5Authenticator *self)
{
    YumeRBTreeNode *n;

    while ((n = yume_rbtree_first (&self->tree))) {
        YumeSocks5User *t;

        t = container_of (n, YumeSocks5User, node);
        yume_rbtree_erase (&self->tree, n);
        yume_object_unref (YUME_OBJECT (t));
    }
}

int
yume_socks5_authenticator_construct (YumeSocks5Authenticator *self)
{
    int res;

    res = yume_object_atomic_construct (&self->base);
    if (res < 0)
        return res;

    LOG_D ("%p socks5 authenticator construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_AUTHENTICATOR_TYPE;

    return 0;
}

static void
yume_socks5_authenticator_destruct (YumeObject *base)
{
    YumeSocks5Authenticator *self = YUME_SOCKS5_AUTHENTICATOR (base);

    LOG_D ("%p socks5 authenticator destruct", self);

    yume_socks5_authenticator_clear (self);

    YUME_OBJECT_ATOMIC_TYPE->destruct (base);
    free (base);
}

YumeObjectClass *
yume_socks5_authenticator_class (void)
{
    static YumeSocks5AuthenticatorClass klass;
    YumeSocks5AuthenticatorClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        memcpy (kptr, YUME_OBJECT_ATOMIC_TYPE, sizeof (YumeObjectAtomicClass));

        okptr->name = "YumeSocks5Authenticator";
        okptr->destruct = yume_socks5_authenticator_destruct;
    }

    return okptr;
}
