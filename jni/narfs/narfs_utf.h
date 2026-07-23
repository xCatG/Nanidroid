#ifndef NANIDROID_NARFS_UTF_H
#define NANIDROID_NARFS_UTF_H

#include <stddef.h>
#include <stdint.h>

typedef enum narfs_utf_status {
    NARFS_UTF_OK = 0, NARFS_UTF_INVALID = 1, NARFS_UTF_LIMIT = 2
} narfs_utf_status;

narfs_utf_status narfs_utf16_to_utf8(
        const uint16_t *input, size_t length,
        unsigned char *output, size_t capacity, size_t *written);
narfs_utf_status narfs_utf8_to_utf16(
        const unsigned char *input, size_t length,
        uint16_t *output, size_t capacity, size_t *written);

#endif
