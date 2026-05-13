/*
 ============================================================================
 Name        : yume-socks5-user.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
 Description : Socks5 User
 ============================================================================
 */

#include <string.h>
#include <stdlib.h>

#include "yume-socks5-logger-priv.h"

#include "yume-socks5-user.h"

YumeSocks5User *
yume_socks5_user_new (const char *name, unsigned int name_len, const char *pass,
                     unsigned int pass_len)
{
    YumeSocks5User *self;
    int res;

    self = calloc (1, sizeof (YumeSocks5User));
    if (!self)
        return NULL;

    res = yume_socks5_user_construct (self, name, name_len, pass, pass_len);
    if (res < 0) {
        free (self);
        return NULL;
    }

    LOG_D ("%p socks5 user new", self);

    return self;
}

int
yume_socks5_user_check (YumeSocks5User *self, const char *pass,
                       unsigned int pass_len)
{
    YumeSocks5UserClass *klass = YUME_OBJECT_GET_CLASS (self);

    return klass->checker (self, pass, pass_len);
}

static int
yume_socks5_user_checker (YumeSocks5User *self, const char *pass,
                         unsigned int pass_len)
{
    LOG_D ("%p socks5 user checker", self);

    if (self->pass_len != pass_len)
        return -1;

    if (memcmp (self->pass, pass, pass_len) != 0)
        return -1;

    return 0;
}

int
yume_socks5_user_construct (YumeSocks5User *self, const char *name,
                           unsigned int name_len, const char *pass,
                           unsigned int pass_len)
{
    int res;

    res = yume_object_atomic_construct (&self->base);
    if (res < 0)
        return res;

    LOG_D ("%p socks5 user construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_USER_TYPE;

    self->name = malloc (name_len);
    self->name_len = name_len;
    memcpy (self->name, name, name_len);

    self->pass = malloc (pass_len);
    self->pass_len = pass_len;
    memcpy (self->pass, pass, pass_len);

    return 0;
}

static void
yume_socks5_user_destruct (YumeObject *base)
{
    YumeSocks5User *self = YUME_SOCKS5_USER (base);

    LOG_D ("%p socks5 user destruct", self);

    free (self->name);
    free (self->pass);

    YUME_OBJECT_ATOMIC_TYPE->destruct (base);
    free (base);
}

YumeObjectClass *
yume_socks5_user_class (void)
{
    static YumeSocks5UserClass klass;
    YumeSocks5UserClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        memcpy (kptr, YUME_OBJECT_ATOMIC_TYPE, sizeof (YumeObjectAtomicClass));

        okptr->name = "YumeSocks5User";
        okptr->destruct = yume_socks5_user_destruct;

        kptr->checker = yume_socks5_user_checker;
    }

    return okptr;
}
