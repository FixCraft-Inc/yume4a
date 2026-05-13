/*
 ============================================================================
 Name        : yume-task-dns-proxy.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 everyone.
 Description : DNS Proxy
 ============================================================================
 */

#ifndef __YUME_TASK_DNS_PROXY_H__
#define __YUME_TASK_DNS_PROXY_H__

#include <netdb.h>
#include <sys/types.h>
#include <sys/socket.h>

typedef struct _YumeTaskDNSProxy YumeTaskDNSProxy;

YumeTaskDNSProxy *yume_task_dns_proxy_new (void);
void yume_task_dns_proxy_destroy (YumeTaskDNSProxy *self);

int yume_task_dns_proxy_getaddrinfo (YumeTaskDNSProxy *self, const char *node,
                                    const char *service,
                                    const struct addrinfo *hints,
                                    struct addrinfo **res);

int yume_task_dns_proxy_getnameinfo (YumeTaskDNSProxy *self,
                                    const struct sockaddr *addr,
                                    socklen_t addrlen, char *node,
                                    socklen_t nodelen, char *service,
                                    socklen_t servicelen, int flags);

#endif /* __YUME_TASK_DNS_PROXY_H__ */
