/*
 ============================================================================
 Name        : yume-circular-buffer.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Circular buffer
 ============================================================================
 */

#include "lib/misc/yume-compiler.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-circular-buffer.h"

struct _YumeCircularBuffer
{
    size_t rp;
    size_t use_size;
    size_t max_size;
    unsigned int ref_count;

    unsigned char data[0];
};

EXPORT_SYMBOL YumeCircularBuffer *
yume_circular_buffer_new (size_t max_size)
{
    YumeCircularBuffer *self;

    self = yume_malloc (sizeof (YumeCircularBuffer) + max_size);
    if (!self)
        return NULL;

    self->rp = 0;
    self->use_size = 0;
    self->max_size = max_size;
    self->ref_count = 1;

    return self;
}

EXPORT_SYMBOL YumeCircularBuffer *
yume_circular_buffer_ref (YumeCircularBuffer *self)
{
    self->ref_count++;

    return self;
}

EXPORT_SYMBOL void
yume_circular_buffer_unref (YumeCircularBuffer *self)
{
    self->ref_count--;
    if (self->ref_count)
        return;

    yume_free (self);
}

EXPORT_SYMBOL size_t
yume_circular_buffer_get_max_size (YumeCircularBuffer *self)
{
    return self->max_size;
}

EXPORT_SYMBOL size_t
yume_circular_buffer_get_use_size (YumeCircularBuffer *self)
{
    return self->use_size;
}

EXPORT_SYMBOL int
yume_circular_buffer_reading (YumeCircularBuffer *self, struct iovec *iov)
{
    size_t upper_size = self->max_size - self->rp;

    if (0 == self->use_size)
        return 0;

    iov[0].iov_base = self->data + self->rp;
    if (self->use_size <= upper_size) {
        iov[0].iov_len = self->use_size;
        return 1;
    }

    iov[0].iov_len = upper_size;
    iov[1].iov_base = self->data;
    iov[1].iov_len = self->use_size - upper_size;
    return 2;
}

EXPORT_SYMBOL void
yume_circular_buffer_read_finish (YumeCircularBuffer *self, size_t size)
{
    self->rp = (self->rp + size) % self->max_size;
    self->use_size -= size;
}

EXPORT_SYMBOL int
yume_circular_buffer_writing (YumeCircularBuffer *self, struct iovec *iov)
{
    size_t wp = (self->rp + self->use_size) % self->max_size;
    size_t upper_size = self->max_size - wp;
    size_t spc_size = self->max_size - self->use_size;

    if (self->use_size == self->max_size)
        return 0;

    iov[0].iov_base = self->data + wp;
    if (spc_size <= upper_size) {
        iov[0].iov_len = spc_size;
        return 1;
    }

    iov[0].iov_len = upper_size;
    iov[1].iov_base = self->data;
    iov[1].iov_len = spc_size - upper_size;
    return 2;
}

EXPORT_SYMBOL void
yume_circular_buffer_write_finish (YumeCircularBuffer *self, size_t size)
{
    self->use_size += size;
}
