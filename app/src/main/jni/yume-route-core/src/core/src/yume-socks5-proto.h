/*
 ============================================================================
 Name        : yume-socks5-proto.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2023 hev
 Description : Socks5 Proto
 ============================================================================
 */

#ifndef __YUME_SOCKS5_PROTO_H__
#define __YUME_SOCKS5_PROTO_H__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum _YumeSocks5Version YumeSocks5Version;
typedef enum _YumeSocks5AuthMethod YumeSocks5AuthMethod;
typedef enum _YumeSocks5AuthVersion YumeSocks5AuthVersion;
typedef enum _YumeSocks5ReqCmd YumeSocks5ReqCmd;
typedef enum _YumeSocks5ResRep YumeSocks5ResRep;
typedef enum _YumeSocks5AddrType YumeSocks5AddrType;

typedef struct _YumeSocks5Auth YumeSocks5Auth;
typedef struct _YumeSocks5Addr YumeSocks5Addr;
typedef struct _YumeSocks5ReqRes YumeSocks5ReqRes;
typedef struct _YumeSocks5UDPHdr YumeSocks5UDPHdr;

enum _YumeSocks5Version
{
    YUME_SOCKS5_VERSION_5 = 5,
};

enum _YumeSocks5AuthMethod
{
    YUME_SOCKS5_AUTH_METHOD_NONE = 0,
    YUME_SOCKS5_AUTH_METHOD_USER = 2,
    YUME_SOCKS5_AUTH_METHOD_DENY = 255,
};

enum _YumeSocks5AuthVersion
{
    YUME_SOCKS5_AUTH_VERSION_1 = 1,
};

enum _YumeSocks5ReqCmd
{
    YUME_SOCKS5_REQ_CMD_CONNECT = 1,
    YUME_SOCKS5_REQ_CMD_UDP_ASC = 3,
    YUME_SOCKS5_REQ_CMD_FWD_UDP = 5,
};

enum _YumeSocks5ResRep
{
    YUME_SOCKS5_RES_REP_SUCC = 0,
    YUME_SOCKS5_RES_REP_FAIL = 1,
    YUME_SOCKS5_RES_REP_HOST = 4,
    YUME_SOCKS5_RES_REP_IMPL = 7,
    YUME_SOCKS5_RES_REP_ADDR = 8,
};

enum _YumeSocks5AddrType
{
    YUME_SOCKS5_ADDR_TYPE_IPV4 = 1,
    YUME_SOCKS5_ADDR_TYPE_IPV6 = 4,
    YUME_SOCKS5_ADDR_TYPE_NAME = 3,
};

struct _YumeSocks5Auth
{
    uint8_t ver;
    union
    {
        uint8_t method;
        uint8_t method_len;
    };
    uint8_t methods[256];
} __attribute__ ((packed));

struct _YumeSocks5Addr
{
    uint8_t atype;
    union
    {
        struct
        {
            uint8_t addr[4];
            uint16_t port;
        } ipv4 __attribute__ ((packed));
        struct
        {
            uint8_t addr[16];
            uint16_t port;
        } ipv6 __attribute__ ((packed));
        struct
        {
            uint8_t len;
            uint8_t addr[256 + 2];
        } domain;
    };
} __attribute__ ((packed));

struct _YumeSocks5ReqRes
{
    uint8_t ver;
    union
    {
        uint8_t cmd;
        uint8_t rep;
    };
    uint8_t rsv;
    YumeSocks5Addr addr;
} __attribute__ ((packed));

struct _YumeSocks5UDPHdr
{
    union
    {
        uint8_t rsv[3];
        struct
        {
            uint16_t datlen;
            uint8_t hdrlen;
        } __attribute__ ((packed));
    };
    YumeSocks5Addr addr;
} __attribute__ ((packed));

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_PROTO_H__ */
