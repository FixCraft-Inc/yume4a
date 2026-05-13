/*
 ============================================================================
 Name        : yume-route-main.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Main
 ============================================================================
 */

#ifndef __YUME_ROUTE_MAIN_H__
#define __YUME_ROUTE_MAIN_H__

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * yume_route_main:
 * @config_path: config file path
 * @tun_fd: tunnel file descriptor
 *
 * Start and run the Yume route backend, this function will blocks until the
 * yume_route_stop is called or an error occurs.
 *
 * Returns: returns zero on successful, otherwise returns -1.
 *
 * Since: 2.4.6
 */
int yume_route_main (const char *config_path, int tun_fd);

/**
 * yume_route_main_from_file:
 * @config_path: config file path
 * @tun_fd: tunnel file descriptor
 *
 * Start and run the Yume route backend, this function will blocks until the
 * yume_route_stop is called or an error occurs.
 *
 * Returns: returns zero on successful, otherwise returns -1.
 *
 * Since: 2.6.7
 */
int yume_route_main_from_file (const char *config_path, int tun_fd);

/**
 * yume_route_main_from_string:
 * @config_str: string config
 * @config_len: the byte length of string config
 * @tun_fd: tunnel file descriptor
 *
 * Start and run the Yume route backend, this function will blocks until the
 * yume_route_stop is called or an error occurs.
 *
 * Returns: returns zero on successful, otherwise returns -1.
 *
 * Since: 2.6.7
 */
int yume_route_main_from_string (const unsigned char *config_str,
                                     unsigned int config_len, int tun_fd);

/**
 * yume_route_stop:
 *
 * Stop the Yume route backend.
 *
 * Since: 2.4.6
 */
void yume_route_stop (void);

/**
 * yume_route_stats:
 * @tx_packets (out): transmitted packets
 * @tx_bytes (out): transmitted bytes
 * @rx_packets (out): received packets
 * @rx_bytes (out): received bytes
 *
 * Retrieve tunnel interface traffic statistics.
 *
 * Since: 2.6.5
 */
void yume_route_stats (size_t *tx_packets, size_t *tx_bytes,
                              size_t *rx_packets, size_t *rx_bytes);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_ROUTE_MAIN_H__ */
