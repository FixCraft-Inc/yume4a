/*
 ============================================================================
 Name        : yume-logger.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Logger
 ============================================================================
 */

#ifndef __YUME_LOGGER_H__
#define __YUME_LOGGER_H__

#define LOG_D(fmt...) yume_logger_log (YUME_LOGGER_DEBUG, fmt)
#define LOG_I(fmt...) yume_logger_log (YUME_LOGGER_INFO, fmt)
#define LOG_W(fmt...) yume_logger_log (YUME_LOGGER_WARN, fmt)
#define LOG_E(fmt...) yume_logger_log (YUME_LOGGER_ERROR, fmt)

#define LOG_ON() yume_logger_enabled (YUME_LOGGER_UNSET)
#define LOG_ON_D() yume_logger_enabled (YUME_LOGGER_DEBUG)
#define LOG_ON_I() yume_logger_enabled (YUME_LOGGER_INFO)
#define LOG_ON_W() yume_logger_enabled (YUME_LOGGER_WARN)
#define LOG_ON_E() yume_logger_enabled (YUME_LOGGER_ERROR)

typedef enum
{
    YUME_LOGGER_DEBUG,
    YUME_LOGGER_INFO,
    YUME_LOGGER_WARN,
    YUME_LOGGER_ERROR,
    YUME_LOGGER_UNSET,
} YumeLoggerLevel;

int yume_logger_init (YumeLoggerLevel level, const char *path);
void yume_logger_fini (void);

int yume_logger_enabled (YumeLoggerLevel level);
void yume_logger_log (YumeLoggerLevel level, const char *fmt, ...);

#endif /* __YUME_LOGGER_H__ */
