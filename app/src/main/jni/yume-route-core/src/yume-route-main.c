/*
 ============================================================================
 Name        : yume-route-main.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Main
 ============================================================================
 */

#include <stdio.h>
#include <signal.h>
#include <string.h>

#include <lwip/init.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-socks5-misc.h>

#include "yume-utils.h"
#include "yume-route-config.h"
#include "yume-route-config-const.h"
#include "yume-logger.h"
#include "yume-socks5-logger.h"
#include "yume-route-core.h"

#include "yume-route-main.h"

static int
yume_route_main_inner (int tun_fd)
{
    const char *pid_file;
    const char *log_file;
    int log_level;
    int nofile;
    int res;

    log_file = yume_route_config_get_misc_log_file ();
    log_level = yume_route_config_get_misc_log_level ();

    res = yume_route_config_get_misc_connect_timeout ();
    yume_socks5_set_connect_timeout (res);
    res = yume_route_config_get_misc_tcp_read_write_timeout ();
    yume_socks5_set_tcp_timeout (res);
    res = yume_route_config_get_misc_udp_read_write_timeout ();
    yume_socks5_set_udp_timeout (res);

    res = yume_route_config_get_misc_udp_recv_buffer_size ();
    yume_socks5_set_udp_recv_buffer_size (res);

    res = yume_logger_init (log_level, log_file);
    if (res < 0)
        return -2;

    res = yume_socks5_logger_init (log_level, log_file);
    if (res < 0)
        return -3;

    nofile = yume_route_config_get_misc_limit_nofile ();
    res = set_limit_nofile (nofile);
    if (res < 0)
        LOG_I ("set limit nofile");

    pid_file = yume_route_config_get_misc_pid_file ();
    if (pid_file)
        run_as_daemon (pid_file);

    res = yume_task_system_init ();
    if (res < 0)
        return -4;

    lwip_init ();

    res = yume_route_init (tun_fd);
    if (res < 0)
        return -5;

    yume_route_run ();

    yume_route_fini ();
    yume_socks5_logger_fini ();
    yume_logger_fini ();
    yume_route_config_fini ();
    yume_task_system_fini ();

    return 0;
}

int
yume_route_main_from_file (const char *config_path, int tun_fd)
{
    int res = yume_route_config_init_from_file (config_path);
    if (res < 0)
        return -1;

    return yume_route_main_inner (tun_fd);
}

int
yume_route_main_from_string (const unsigned char *config_str,
                                 unsigned int config_len, int tun_fd)
{
    int res = yume_route_config_init_from_str (config_str, config_len);
    if (res < 0)
        return -1;

    return yume_route_main_inner (tun_fd);
}

int
yume_route_main (const char *config_path, int tun_fd)
{
    return yume_route_main_from_file (config_path, tun_fd);
}

void
yume_route_stop (void)
{
    yume_route_request_stop ();
}

#ifndef ENABLE_LIBRARY
static void
show_help (const char *self_path)
{
    printf ("%s CONFIG_PATH\n", self_path);
    printf ("Version: %u.%u.%u %s\n", MAJOR_VERSION, MINOR_VERSION,
            MICRO_VERSION, COMMIT_ID);
}

static void
sigint_handler (int signum)
{
    yume_route_request_stop ();
}

int
main (int argc, char *argv[])
{
    int res;

    if (argc < 2 || strcmp (argv[1], "--version") == 0) {
        show_help (argv[0]);
        return -1;
    }

    signal (SIGINT, sigint_handler);

    res = yume_route_main (argv[1], -1);
    if (res < 0)
        return -2;

    return 0;
}
#endif /* ENABLE_LIBRARY */
