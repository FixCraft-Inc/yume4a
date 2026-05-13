/*
 ============================================================================
 Name        : yume-object.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 hev
 Description : Object
 ============================================================================
 */

#include "lib/misc/yume-compiler.h"

#include "yume-object.h"

EXPORT_SYMBOL int
yume_object_construct (YumeObject *self)
{
    self->klass = YUME_OBJECT_TYPE;
    self->ref_count = 1;

    return 0;
}

static void
_yume_object_destruct (YumeObject *self)
{
}

static void
_yume_object_ref (YumeObject *self, int ref)
{
    YumeObjectClass *kptr = YUME_OBJECT_GET_CLASS (self);

    if (ref) {
        self->ref_count++;
        return;
    }

    self->ref_count--;
    if (self->ref_count)
        return;

    kptr->destruct (self);
}

EXPORT_SYMBOL YumeObjectClass *
yume_object_class (void)
{
    static YumeObjectClass klass;
    YumeObjectClass *kptr = &klass;

    if (!kptr->name) {
        kptr->name = "YumeObject";
        kptr->destruct = _yume_object_destruct;
        kptr->ref = _yume_object_ref;
    }

    return kptr;
}
