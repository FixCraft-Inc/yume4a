/*
 ============================================================================
 Name        : yume-socks5-logger.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 hev
 Description : Socks5 Logger
 ============================================================================
 */

#ifndef __YUME_SOCKS5_LOGGER_H__
#define __YUME_SOCKS5_LOGGER_H__

#ifdef __cplusplus
extern "C" {
#endif

typedef enum _YumeSocks5LoggerLevel YumeSocks5LoggerLevel;

enum _YumeSocks5LoggerLevel
{
    YUME_SOCKS5_LOGGER_DEBUG,
    YUME_SOCKS5_LOGGER_INFO,
    YUME_SOCKS5_LOGGER_WARN,
    YUME_SOCKS5_LOGGER_ERROR,
    YUME_SOCKS5_LOGGER_UNSET,
};

int yume_socks5_logger_init (YumeSocks5LoggerLevel level, const char *path);
void yume_socks5_logger_fini (void);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_LOGGER_H__ */
