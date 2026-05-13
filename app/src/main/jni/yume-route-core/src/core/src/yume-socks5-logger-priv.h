/*
 ============================================================================
 Name        : yume-socks5-logger-priv.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 hev
 Description : Socks5 Logger Private
 ============================================================================
 */

#ifndef __YUME_SOCKS5_LOGGER_PRIV_H__
#define __YUME_SOCKS5_LOGGER_PRIV_H__

#include "yume-socks5-logger.h"

#ifdef __cplusplus
extern "C" {
#endif

#define LOG_D(fmt...) yume_socks5_logger_log (YUME_SOCKS5_LOGGER_DEBUG, fmt)
#define LOG_I(fmt...) yume_socks5_logger_log (YUME_SOCKS5_LOGGER_INFO, fmt)
#define LOG_W(fmt...) yume_socks5_logger_log (YUME_SOCKS5_LOGGER_WARN, fmt)
#define LOG_E(fmt...) yume_socks5_logger_log (YUME_SOCKS5_LOGGER_ERROR, fmt)

#define LOG_ON() yume_socks5_logger_enabled (YUME_SOCKS5_LOGGER_UNSET)
#define LOG_ON_D() yume_socks5_logger_enabled (YUME_SOCKS5_LOGGER_DEBUG)
#define LOG_ON_I() yume_socks5_logger_enabled (YUME_SOCKS5_LOGGER_INFO)
#define LOG_ON_W() yume_socks5_logger_enabled (YUME_SOCKS5_LOGGER_WARN)
#define LOG_ON_E() yume_socks5_logger_enabled (YUME_SOCKS5_LOGGER_ERROR)

int yume_socks5_logger_enabled (YumeSocks5LoggerLevel level);
void yume_socks5_logger_log (YumeSocks5LoggerLevel level, const char *fmt, ...);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_LOGGER_PRIV_H__ */
