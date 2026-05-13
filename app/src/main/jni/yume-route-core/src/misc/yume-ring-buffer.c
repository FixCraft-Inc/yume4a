/*
 ============================================================================
 Name        : yume-ring-buffer.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
 Description : Ring buffer
 ============================================================================
 */

#include "yume-compiler.h"
#include "yume-ring-buffer.h"

size_t
yume_ring_buffer_get_max_size (YumeRingBuffer *self)
{
    return self->max_size;
}

size_t
yume_ring_buffer_get_use_size (YumeRingBuffer *self)
{
    return self->use_size;
}

int
yume_ring_buffer_reading (YumeRingBuffer *self, struct iovec *iov)
{
    size_t upper_size;

    if (0 == self->rda_size)
        return 0;

    upper_size = self->max_size - self->rp;

    iov[0].iov_base = self->data + self->rp;
    if (self->rda_size <= upper_size) {
        iov[0].iov_len = self->rda_size;
        return 1;
    }

    iov[0].iov_len = upper_size;
    iov[1].iov_base = self->data;
    iov[1].iov_len = self->rda_size - upper_size;
    return 2;
}

void
yume_ring_buffer_read_finish (YumeRingBuffer *self, size_t size)
{
    self->rp = (self->rp + size) % self->max_size;
    self->rda_size -= size;
}

void
yume_ring_buffer_read_release (YumeRingBuffer *self, size_t size)
{
    self->use_size -= size;
    if (!self->use_size) {
        self->rp = 0;
        self->wp = 0;
    }
}

int
yume_ring_buffer_writing (YumeRingBuffer *self, struct iovec *iov)
{
    size_t upper_size, spc_size;

    if (self->use_size == self->max_size)
        return 0;

    upper_size = self->max_size - self->wp;
    spc_size = self->max_size - self->use_size;

    iov[0].iov_base = self->data + self->wp;
    if (spc_size <= upper_size) {
        iov[0].iov_len = spc_size;
        return 1;
    }

    iov[0].iov_len = upper_size;
    iov[1].iov_base = self->data;
    iov[1].iov_len = spc_size - upper_size;
    return 2;
}

void
yume_ring_buffer_write_finish (YumeRingBuffer *self, size_t size)
{
    self->wp = (self->wp + size) % self->max_size;
    self->use_size += size;
    self->rda_size += size;
}
