/*
 ============================================================================
 Name        : yume-socks5-user.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
 Description : Socks5 User
 ============================================================================
 */

#ifndef __YUME_SOCKS5_USER_H__
#define __YUME_SOCKS5_USER_H__

#include <yume-object-atomic.h>

#include "yume-rbtree.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_USER(p) ((YumeSocks5User *)p)
#define YUME_SOCKS5_USER_CLASS(p) ((YumeSocks5UserClass *)p)
#define YUME_SOCKS5_USER_TYPE (yume_socks5_user_class ())

typedef struct _YumeSocks5User YumeSocks5User;
typedef struct _YumeSocks5UserClass YumeSocks5UserClass;

struct _YumeSocks5User
{
    YumeObjectAtomic base;

    YumeRBTreeNode node;

    char *name;
    char *pass;
    unsigned int name_len;
    unsigned int pass_len;
};

struct _YumeSocks5UserClass
{
    YumeObjectAtomicClass base;

    int (*checker) (YumeSocks5User *self, const char *pass,
                    unsigned int pass_len);
};

YumeObjectClass *yume_socks5_user_class (void);

int yume_socks5_user_construct (YumeSocks5User *self, const char *name,
                               unsigned int name_len, const char *pass,
                               unsigned int pass_len);

YumeSocks5User *yume_socks5_user_new (const char *name, unsigned int name_len,
                                    const char *pass, unsigned int pass_len);

int yume_socks5_user_check (YumeSocks5User *self, const char *pass,
                           unsigned int pass_len);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_USER_H__ */
