/* SPDX-License-Identifier: MIT */

#ifndef UDROID_AHB_TRANSPORT_PROTOCOL_H
#define UDROID_AHB_TRANSPORT_PROTOCOL_H

#include <stdint.h>

#define UDROID_AHB_TRANSPORT_MAGIC UINT32_C(0x55444842) /* "UDHB" */
#define UDROID_AHB_TRANSPORT_VERSION UINT32_C(4)
#define UDROID_AHB_TRANSPORT_MIN_VERSION UINT32_C(3)

enum UdroidAhbTransportPacketKind {
    UDROID_AHB_REGISTER_BUFFER = 1,
    UDROID_AHB_ACQUIRE_FENCE = 2,
    UDROID_AHB_RELEASE_FENCE = 3,
    UDROID_AHB_REUSE_READY = 4,
    UDROID_AHB_RETIRE_BUFFER = 5,
    /*
     * Immediate response to ACQUIRE_FENCE. SCM_RIGHTS carries exactly two
     * descriptors, in this order: current-frame presentation completion,
     * then buffer reuse/release completion.
     */
    UDROID_AHB_FRAME_SIGNALS = 6,
    /*
     * Non-blocking v4 acquire. SCM_RIGHTS carries exactly three descriptors:
     * producer completion, presentation completion, then buffer release.
     * The presenter signals the latter two descriptors instead of replying.
     */
    UDROID_AHB_ACQUIRE_WITH_SIGNALS = 7,
};

/*
 * Fixed-width framing around Android's public AHardwareBuffer Unix-socket
 * helpers. All integer fields use the local Android ABI byte order; the
 * transport is local-only and is not a network protocol. Registration and
 * retirement carry frame_id zero. Acquire, release, frame-signals, and
 * reuse-ready messages carry the same positive frame identity for one
 * resource generation. Protocol v4 keeps the fixed packet layout and removes
 * the per-frame response round trip for ACQUIRE_WITH_SIGNALS. The presenter
 * continues to accept v3 so an app update can drain an older bundled host.
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
