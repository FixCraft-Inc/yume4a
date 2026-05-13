/*
 ============================================================================
 Name        : yume-socks5-authenticator.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
 Description : Socks5 Authenticator
 ============================================================================
 */

#ifndef __YUME_SOCKS5_AUTHENTICATOR_H__
#define __YUME_SOCKS5_AUTHENTICATOR_H__

#include <yume-object-atomic.h>

#include "yume-rbtree.h"
#include "yume-socks5-user.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_AUTHENTICATOR(p) ((YumeSocks5Authenticator *)p)
#define YUME_SOCKS5_AUTHENTICATOR_CLASS(p) ((YumeSocks5AuthenticatorClass *)p)
#define YUME_SOCKS5_AUTHENTICATOR_TYPE (yume_socks5_authenticator_class ())

typedef struct _YumeSocks5Authenticator YumeSocks5Authenticator;
typedef struct _YumeSocks5AuthenticatorClass YumeSocks5AuthenticatorClass;
typedef enum _YumeSocks5AuthenticatorType YumeSocks5AuthenticatorType;

struct _YumeSocks5Authenticator
{
    YumeObjectAtomic base;

    YumeRBTree tree;
};

struct _YumeSocks5AuthenticatorClass
{
    YumeObjectAtomicClass base;
};

YumeObjectClass *yume_socks5_authenticator_class (void);

int yume_socks5_authenticator_construct (YumeSocks5Authenticator *self);

YumeSocks5Authenticator *yume_socks5_authenticator_new (void);

int yume_socks5_authenticator_add (YumeSocks5Authenticator *self,
                                  YumeSocks5User *user);

int yume_socks5_authenticator_del (YumeSocks5Authenticator *self,
                                  const char *name, unsigned int name_len);

YumeSocks5User *yume_socks5_authenticator_get (YumeSocks5Authenticator *self,
                                             const char *name,
                                             unsigned int name_len);

void yume_socks5_authenticator_clear (YumeSocks5Authenticator *self);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_AUTHENTICATOR_H__ */
