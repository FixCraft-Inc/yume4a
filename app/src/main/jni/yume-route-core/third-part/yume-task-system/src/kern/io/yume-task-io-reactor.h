/*
 ============================================================================
 Name        : yume-task-io-reactor.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : I/O Reactor
 ============================================================================
 */

#ifndef __YUME_TASK_IO_REACTOR_H__
#define __YUME_TASK_IO_REACTOR_H__

typedef struct _YumeTaskIOReactor YumeTaskIOReactor;
typedef enum _YumeTaskIOReactorEvents YumeTaskIOReactorEvents;
typedef enum _YumeTaskIOReactorOperation YumeTaskIOReactorOperation;

struct _YumeTaskIOReactor
{
    union
    {
        int fd;
        void *handle;
    };
};

#if defined(__MSYS__)
#include "kern/io/yume-task-io-reactor-iocp.h"
#elif defined(__linux__)
#include "kern/io/yume-task-io-reactor-epoll.h"
#else
#include "kern/io/yume-task-io-reactor-kqueue.h"
#endif

YumeTaskIOReactor *yume_task_io_reactor_new (void);
void yume_task_io_reactor_destroy (YumeTaskIOReactor *self);

int yume_task_io_reactor_setup (YumeTaskIOReactor *self,
                               YumeTaskIOReactorSetupEvent *events, int count);

#endif /* __YUME_TASK_IO_REACTOR_H__ */
