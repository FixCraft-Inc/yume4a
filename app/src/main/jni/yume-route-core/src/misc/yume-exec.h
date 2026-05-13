/*
 ============================================================================
 Name        : yume-exec.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 - 2025 hev
 Description : Exec
 ============================================================================
 */

#ifndef __YUME_EXEC_H__
#define __YUME_EXEC_H__

void yume_exec_run (const char *script_path, const char *tun_name,
                   const char *tun_index, int wait);

#endif /* __YUME_EXEC_H__ */
