/*
 ============================================================================
 Name        : mem.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2021 hev
 Description : Memory
 ============================================================================
 */

#include <stdlib.h>

#if defined(_WIN32) || defined(_WIN64)
#define WEAK
#else
#define WEAK __attribute__((weak))
#endif

WEAK void
yume_free (void *ptr)
{
    free (ptr);
}

WEAK void *
yume_malloc (size_t size)
{
    return malloc (size);
}

WEAK void *
yume_calloc (size_t nmemb, size_t size)
{
    return calloc (nmemb, size);
}
