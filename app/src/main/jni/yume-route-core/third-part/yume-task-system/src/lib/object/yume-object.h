/*
 ============================================================================
 Name        : yume-object.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 hev
 Description : Object
 ============================================================================
 */

#ifndef __YUME_OBJECT_H__
#define __YUME_OBJECT_H__

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_OBJECT(p) ((YumeObject *)p)
#define YUME_OBJECT_CLASS(p) ((YumeObjectClass *)p)
#define YUME_OBJECT_GET_CLASS(p) ((void *)((YumeObject *)p)->klass)
#define YUME_OBJECT_GET_IFACE(p, i) (((YumeObject *)p)->klass->iface (p, i))
#define YUME_OBJECT_TYPE (yume_object_class ())

typedef struct _YumeObject YumeObject;
typedef struct _YumeObjectClass YumeObjectClass;

struct _YumeObject
{
    YumeObjectClass *klass;

    unsigned int ref_count;
};

struct _YumeObjectClass
{
    const char *name;

    void (*ref) (YumeObject *, int);
    void (*destruct) (YumeObject *);
    void *(*iface) (YumeObject *, void *);
};

/**
 * yume_object_class:
 *
 * Get the class of #YumeObject.
 *
 * Returns: a #YumeObjectClass
 *
 * Since: 5.3
 */
YumeObjectClass *yume_object_class (void);

/**
 * yume_object_construct:
 * @self: a #YumeObject
 *
 * Construct a new object.
 *
 * Returns: When successful, returns zero. otherwise, returns -1.
 *
 * Since: 5.3
 */
int yume_object_construct (YumeObject *self);

/**
 * yume_object_ref:
 * @self: a #YumeObject
 *
 * Increases the reference count of the @self by one.
 *
 * Since: 5.3
 */
static inline void
yume_object_ref (YumeObject *self)
{
    YumeObjectClass *kptr = YUME_OBJECT_GET_CLASS (self);
    kptr->ref (self, 1);
}

/**
 * yume_object_unref:
 * @self: a #YumeObject
 *
 * Decreases the reference count of @self. When its reference count
 * drops to 0, the object is finalized (i.e. its memory is freed).
 *
 * Since: 5.3
 */
static inline void
yume_object_unref (YumeObject *self)
{
    YumeObjectClass *kptr = YUME_OBJECT_GET_CLASS (self);
    kptr->ref (self, 0);
}

#ifdef __cplusplus
}
#endif

#endif /* __YUME_OBJECT_H__ */
