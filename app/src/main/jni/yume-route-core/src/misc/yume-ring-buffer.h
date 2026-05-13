/*
 ============================================================================
 Name        : yume-ring-buffer.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 hev
 Description : Ring buffer
 ============================================================================
 */

#ifndef __YUME_RING_BUFFER_H__
#define __YUME_RING_BUFFER_H__

#include <stdlib.h>
#include <sys/uio.h>
#include <sys/types.h>

typedef struct _YumeRingBuffer YumeRingBuffer;

struct _YumeRingBuffer
{
    size_t rp;
    size_t wp;
    size_t rda_size;
    size_t use_size;
    size_t max_size;

    unsigned char data[0];
};

#define yume_ring_buffer_alloca(s)                     \
    ({                                                \
        YumeRingBuffer *__self;                        \
                                                      \
        __self = alloca (sizeof (YumeRingBuffer) + s); \
        if (!__self)                                  \
            NULL;                                     \
                                                      \
        __self->rp = 0;                               \
        __self->wp = 0;                               \
        __self->rda_size = 0;                         \
        __self->use_size = 0;                         \
        __self->max_size = s;                         \
                                                      \
        __self;                                       \
    })

size_t yume_ring_buffer_get_max_size (YumeRingBuffer *self);
size_t yume_ring_buffer_get_use_size (YumeRingBuffer *self);

int yume_ring_buffer_reading (YumeRingBuffer *self, struct iovec *iov);
void yume_ring_buffer_read_finish (YumeRingBuffer *self, size_t size);
void yume_ring_buffer_read_release (YumeRingBuffer *self, size_t size);

int yume_ring_buffer_writing (YumeRingBuffer *self, struct iovec *iov);
void yume_ring_buffer_write_finish (YumeRingBuffer *self, size_t size);

#endif /* __YUME_RING_BUFFER_H__ */
