#ifndef NANIDROID_NARFS_STAGE_TOKEN_H
#define NANIDROID_NARFS_STAGE_TOKEN_H

#include "narfs_stage.h"
#include <stdint.h>
#include <string.h>

#define NARFS_STAGE_WIRE_BYTES 88U
#define NARFS_STAGE_WIRE_VERSION 1U

#define NARFS_STAGE_INLINE \
    static __inline__ __attribute__((always_inline))

NARFS_STAGE_INLINE int
narfs_stage_token_session_valid(const narfs_stage_token *token) {
    size_t index;
    if (token == NULL || token->session_name[0] != 's'
            || token->session_name[33] != '\0'
            || token->root_device == 0 || token->root_inode == 0
            || token->stage_device == 0 || token->stage_inode == 0
            || token->root_device > INT64_MAX || token->root_inode > INT64_MAX
            || token->stage_device > INT64_MAX || token->stage_inode > INT64_MAX) {
        return 0;
    }
    for (index = 1; index < 33; index++) {
        const char value = token->session_name[index];
        if (!((value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f'))) return 0;
    }
    return 1;
}

NARFS_STAGE_INLINE void narfs_stage_token_put64(
        unsigned char *output, size_t offset, uint64_t value) {
    size_t index;
    for (index = 0; index < 8; index++) {
        output[offset + 7 - index] = (unsigned char) value;
        value >>= 8;
    }
}

NARFS_STAGE_INLINE uint64_t narfs_stage_token_get64(
        const unsigned char *input, size_t offset) {
    uint64_t value = 0;
    size_t index;
    for (index = 0; index < 8; index++) {
        value = (value << 8) | input[offset + index];
    }
    return value;
}

NARFS_STAGE_INLINE int narfs_stage_token_encode(
        const narfs_stage_token *token,
        unsigned char *output, size_t length) {
    if (output == NULL || length != NARFS_STAGE_WIRE_BYTES
            || !narfs_stage_token_session_valid(token)) return 0;
    memset(output, 0, length);
    memcpy(output, "NSTG", 4);
    output[4] = NARFS_STAGE_WIRE_VERSION;
    output[8] = 33;
    memcpy(output + 16, token->session_name, 33);
    narfs_stage_token_put64(output, 56, token->root_device);
    narfs_stage_token_put64(output, 64, token->root_inode);
    narfs_stage_token_put64(output, 72, token->stage_device);
    narfs_stage_token_put64(output, 80, token->stage_inode);
    return 1;
}

NARFS_STAGE_INLINE int narfs_stage_token_decode(
        const unsigned char *input, size_t length,
        narfs_stage_token *token) {
    static const size_t reserved[] = {
        5, 6, 7, 9, 10, 11, 12, 13, 14, 15,
        49, 50, 51, 52, 53, 54, 55
    };
    size_t index;
    if (token == NULL) return 0;
    memset(token, 0, sizeof(*token));
    if (input == NULL
            || length != NARFS_STAGE_WIRE_BYTES
            || memcmp(input, "NSTG", 4) != 0
            || input[4] != NARFS_STAGE_WIRE_VERSION
            || input[8] != 33) return 0;
    for (index = 0; index < sizeof(reserved) / sizeof(reserved[0]); index++) {
        if (input[reserved[index]] != 0) return 0;
    }
    memcpy(token->session_name, input + 16, 33);
    token->root_device = narfs_stage_token_get64(input, 56);
    token->root_inode = narfs_stage_token_get64(input, 64);
    token->stage_device = narfs_stage_token_get64(input, 72);
    token->stage_inode = narfs_stage_token_get64(input, 80);
    if (!narfs_stage_token_session_valid(token)) {
        memset(token, 0, sizeof(*token));
        return 0;
    }
    return 1;
}

#endif
