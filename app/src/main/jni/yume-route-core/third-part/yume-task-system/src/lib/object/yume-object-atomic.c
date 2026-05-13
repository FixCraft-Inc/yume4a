/*
 ============================================================================
 Name        : yume-object-atomic.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 hev
 Description : Object with atomic ref-count
 ============================================================================
 */

#include <string.h>
#include <stdatomic.h>

#include "lib/misc/yume-compiler.h"

#include "yume-object-atomic.h"

EXPORT_SYMBOL int
yume_object_atomic_construct (YumeObjectAtomic *self)
{
    int res;

    res = yume_object_construct (&self->base);
    if (res < 0)
        return res;

    YUME_OBJECT (self)->klass = YUME_OBJECT_ATOMIC_TYPE;

    return 0;
}

static void
_yume_object_atomic_destruct (YumeObject *base)
{
    YUME_OBJECT_TYPE->destruct (base);
}

static void
_yume_object_atomic_ref (YumeObject *base, int ref)
{
    YumeObjectAtomic *self = YUME_OBJECT_ATOMIC (base);
    YumeObjectClass *kptr = YUME_OBJECT_GET_CLASS (self);
    atomic_uint *rcp = (atomic_uint *)&base->ref_count;
    unsigned int rc;

    if (ref) {
        atomic_fetch_add_explicit (rcp, 1, memory_order_relaxed);
        return;
    }

    rc = atomic_fetch_sub_explicit (rcp, 1, memory_order_relaxed);
    if (rc > 1)
        return;

    kptr->destruct (base);
}

EXPORT_SYMBOL YumeObjectClass *
yume_object_atomic_class (void)
{
    static YumeObjectAtomicClass klass;
    YumeObjectAtomicClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        memcpy (kptr, YUME_OBJECT_TYPE, sizeof (YumeObjectClass));

        okptr->name = "YumeObjectAtomic";
        okptr->destruct = _yume_object_atomic_destruct;
        okptr->ref = _yume_object_atomic_ref;
    }

    return okptr;
}
