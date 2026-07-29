#ifndef NANIDROID_NARFS_SHA256_H
#define NANIDROID_NARFS_SHA256_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NARFS_SHA256_BYTES 32U

typedef struct narfs_sha256 {
    uint32_t state[8];
    uint64_t bits;
    unsigned char block[64];
    size_t used;
} narfs_sha256;

void narfs_sha256_init(narfs_sha256 *context);

/*
 * Context and output pointers must be non-null. Input may be null only when
 * count is zero. final consumes an initialized context; update-after-final,
 * double-final, and overlapping context/input/output buffers are unsupported.
 */
void narfs_sha256_update(
        narfs_sha256 *context, const unsigned char *bytes, size_t count);
void narfs_sha256_final(
        narfs_sha256 *context, unsigned char output[NARFS_SHA256_BYTES]);

#ifdef __cplusplus
}
#endif

#endif
