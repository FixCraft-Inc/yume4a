/*
 ============================================================================
 Name        : yume-wintun.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
 Description : Wintun utils
 ============================================================================
 */

#if defined(__MSYS__)

#include <wintun.h>
#include <netioapi.h>
#include <iphlpapi.h>

#include "yume-wintun.h"

static WINTUN_CREATE_ADAPTER_FUNC *WintunCreateAdapter;
static WINTUN_CLOSE_ADAPTER_FUNC *WintunCloseAdapter;
static WINTUN_GET_ADAPTER_LUID_FUNC *WintunGetAdapterLUID;
static WINTUN_START_SESSION_FUNC *WintunStartSession;
static WINTUN_END_SESSION_FUNC *WintunEndSession;
static WINTUN_GET_READ_WAIT_EVENT_FUNC *WintunGetReadWaitEvent;
static WINTUN_RECEIVE_PACKET_FUNC *WintunReceivePacket;
static WINTUN_RELEASE_RECEIVE_PACKET_FUNC *WintunReleaseReceivePacket;
static WINTUN_ALLOCATE_SEND_PACKET_FUNC *WintunAllocateSendPacket;
static WINTUN_SEND_PACKET_FUNC *WintunSendPacket;

YumeWinTun *
yume_wintun_open (void)
{
    HMODULE self;

    self = LoadLibraryExW (L"wintun.dll", NULL,
                           LOAD_LIBRARY_SEARCH_APPLICATION_DIR |
                               LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!self)
        return NULL;

#define X(Name) ((*(FARPROC *)&Name = GetProcAddress (self, #Name)) == NULL)
    if (X (WintunCreateAdapter) || X (WintunCloseAdapter) ||
        X (WintunGetAdapterLUID) || X (WintunStartSession) ||
        X (WintunEndSession) || X (WintunGetReadWaitEvent) ||
        X (WintunReceivePacket) || X (WintunReleaseReceivePacket) ||
        X (WintunAllocateSendPacket) || X (WintunSendPacket))
        goto free;
#undef X

    return self;

free:
    FreeLibrary (self);
    return NULL;
}

void
yume_wintun_close (YumeWinTun *self)
{
    FreeLibrary (self);
}

YumeWinTunAdapter *
yume_wintun_adapter_create (const char *name)
{
    wchar_t tun[256];
    int len;

    len = MultiByteToWideChar (CP_UTF8, 0, name, -1, NULL, 0);
    if (len >= 256)
        return NULL;

    MultiByteToWideChar (CP_UTF8, 0, name, -1, tun, len);
    return WintunCreateAdapter (tun, L"Socks5", NULL);
}

void
yume_wintun_adapter_close (YumeWinTunAdapter *adapter)
{
    WintunCloseAdapter (adapter);
}

int
yume_wintun_adapter_get_index (YumeWinTunAdapter *adapter)
{
    NET_LUID Luid;
    NET_IFINDEX index;

    WintunGetAdapterLUID (adapter, &Luid);

    if (ConvertInterfaceLuidToIndex (&Luid, &index) != NO_ERROR)
        return -1;

    return index;
}

int
yume_wintun_adapter_set_mtu (YumeWinTunAdapter *adapter, int mtu)
{
    NET_LUID Luid;
    MIB_IFROW IfRow;

    WintunGetAdapterLUID (adapter, &Luid);

    if (ConvertInterfaceLuidToIndex (&Luid, &IfRow.dwIndex) != NO_ERROR)
        return -1;

    if (GetIfEntry (&IfRow) != NO_ERROR)
        return -1;

    IfRow.dwMtu = mtu;

    if (SetIfEntry (&IfRow) != NO_ERROR)
        return -1;

    return 0;
}

int
yume_wintun_adapter_set_ipv4 (YumeWinTunAdapter *adapter, char addr[4],
                             unsigned int prefix)
{
    MIB_UNICASTIPADDRESS_ROW AddressRow;
    DWORD LastError;

    InitializeUnicastIpAddressEntry (&AddressRow);
    WintunGetAdapterLUID (adapter, &AddressRow.InterfaceLuid);

    AddressRow.Address.Ipv4.sin_family = AF_INET;
    memcpy (&AddressRow.Address.Ipv4.sin_addr, addr, 4);
    AddressRow.OnLinkPrefixLength = prefix;
    AddressRow.DadState = IpDadStatePreferred;

    LastError = CreateUnicastIpAddressEntry (&AddressRow);
    if (LastError != ERROR_SUCCESS && LastError != ERROR_OBJECT_ALREADY_EXISTS)
        return -1;

    return 0;
}

int
yume_wintun_adapter_set_ipv6 (YumeWinTunAdapter *adapter, char addr[16],
                             unsigned int prefix)
{
    MIB_UNICASTIPADDRESS_ROW AddressRow;
    DWORD LastError;

    InitializeUnicastIpAddressEntry (&AddressRow);
    WintunGetAdapterLUID (adapter, &AddressRow.InterfaceLuid);

    AddressRow.Address.Ipv6.sin6_family = AF_INET6;
    memcpy (&AddressRow.Address.Ipv6.sin6_addr, addr, 16);
    AddressRow.OnLinkPrefixLength = prefix;
    AddressRow.DadState = IpDadStatePreferred;

    LastError = CreateUnicastIpAddressEntry (&AddressRow);
    if (LastError != ERROR_SUCCESS && LastError != ERROR_OBJECT_ALREADY_EXISTS)
        return -1;

    return 0;
}

YumeWinTunSession *
yume_wintun_session_start (YumeWinTunAdapter *adapter)
{
    return WintunStartSession (adapter, 0x400000);
}

void
yume_wintun_session_stop (YumeWinTunSession *session)
{
    WintunEndSession (session);
}

void *
yume_wintun_session_get_read_wait_event (YumeWinTunSession *session)
{
    return WintunGetReadWaitEvent (session);
}

void *
yume_wintun_session_receive (YumeWinTunSession *session, int *size)
{
    return WintunReceivePacket (session, (DWORD *)size);
}

void
yume_wintun_session_release (YumeWinTunSession *session, void *packet)
{
    WintunReleaseReceivePacket (session, packet);
}

void *
yume_wintun_session_allocate (YumeWinTunSession *session, int size)
{
    return WintunAllocateSendPacket (session, size);
}

void
yume_wintun_session_send (YumeWinTunSession *session, void *packet)
{
    WintunSendPacket (session, packet);
}

unsigned int
yume_wintun_get_last_error (void)
{
    return GetLastError ();
}

#endif /* __MSYS__ */
