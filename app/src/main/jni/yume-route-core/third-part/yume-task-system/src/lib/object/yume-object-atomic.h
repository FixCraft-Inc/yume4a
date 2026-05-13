/*
 ============================================================================
 Name        : yume-object-atomic.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 hev
 Description : Object with atomic ref-count
 ============================================================================
 */

#ifndef __YUME_OBJECT_ATOMIC_H__
#define __YUME_OBJECT_ATOMIC_H__

#include <yume-object.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_OBJECT_ATOMIC(p) ((YumeObjectAtomic *)p)
#define YUME_OBJECT_ATOMIC_CLASS(p) ((YumeObjectAtomicClass *)p)
#define YUME_OBJECT_ATOMIC_TYPE (yume_object_atomic_class ())

typedef struct _YumeObjectAtomic YumeObjectAtomic;
typedef struct _YumeObjectAtomicClass YumeObjectAtomicClass;

struct _YumeObjectAtomic
{
    YumeObject base;
};

struct _YumeObjectAtomicClass
{
    YumeObjectClass base;
};

/**
 * yume_object_atomic_class:
 *
 * Get the class of #YumeObjectAtomic.
 *
 * Returns: a #YumeObjectClass
 *
 * Since: 5.3
 */
YumeObjectClass *yume_object_atomic_class (void);

/**
 * yume_object_atomic_construct:
 * @self: a #YumeObjectAtomic
 *
 * Construct a new object with atomic ref-count.
 *
 * Returns: When successful, returns zero. otherwise, returns -1.
 *
 * Since: 5.3
 */
int yume_object_atomic_construct (YumeObjectAtomic *self);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_OBJECT_ATOMIC_H__ */
