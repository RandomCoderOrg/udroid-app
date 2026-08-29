/* SPDX-License-Identifier: MIT */

#ifndef UDROID_AHB_TRANSPORT_PROTOCOL_H
#define UDROID_AHB_TRANSPORT_PROTOCOL_H

#include <stdint.h>

#define UDROID_AHB_TRANSPORT_MAGIC UINT32_C(0x55444842) /* "UDHB" */
#define UDROID_AHB_TRANSPORT_VERSION UINT32_C(2)

enum UdroidAhbTransportPacketKind {
    UDROID_AHB_REGISTER_BUFFER = 1,
    UDROID_AHB_ACQUIRE_FENCE = 2,
    UDROID_AHB_RELEASE_FENCE = 3,
    UDROID_AHB_REUSE_READY = 4,
    UDROID_AHB_RETIRE_BUFFER = 5,
};

/*
 * Fixed-width framing around Android's public AHardwareBuffer Unix-socket
 * helpers. All integer fields use the local Android ABI byte order; the
 * transport is local-only and is not a network protocol. Registration and
 * retirement carry frame_id zero. Acquire, release, and reuse-ready messages
 * carry the same positive frame identity for one resource generation.
 */
struct UdroidAhbTransportPacket {
    uint32_t magic;
    uint32_t version;
    uint32_t kind;
    uint32_t reserved;
    uint64_t resource_id;
    uint64_t generation;
    uint64_t frame_id;
};

#endif /* UDROID_AHB_TRANSPORT_PROTOCOL_H */
