#include "narfs_utf.h"

narfs_utf_status narfs_utf16_to_utf8(
        const uint16_t *input, size_t length,
        unsigned char *output, size_t capacity, size_t *written) {
    size_t source = 0, target = 0;
    if (input == NULL || output == NULL || written == NULL) return NARFS_UTF_INVALID;
    while (source < length) {
        uint32_t value = input[source++];
        size_t needed;
        if (value == 0) return NARFS_UTF_INVALID;
        if (value >= 0xd800 && value <= 0xdbff) {
            uint32_t low;
            if (source == length) return NARFS_UTF_INVALID;
            low = input[source++];
            if (low < 0xdc00 || low > 0xdfff) return NARFS_UTF_INVALID;
            value = 0x10000 + ((value - 0xd800) << 10) + (low - 0xdc00);
        } else if (value >= 0xdc00 && value <= 0xdfff) {
            return NARFS_UTF_INVALID;
        }
        needed = value < 0x80 ? 1 : value < 0x800 ? 2 : value < 0x10000 ? 3 : 4;
        if (target + needed > capacity) return NARFS_UTF_LIMIT;
        if (needed == 1) output[target++] = (unsigned char) value;
        else {
            if (needed == 4) output[target++] = (unsigned char) (0xf0 | (value >> 18));
            if (needed >= 3) output[target++] = (unsigned char)
                    ((needed == 3 ? 0xe0 : 0x80) | ((value >> 12) & 0x3f));
            output[target++] = (unsigned char)
                    ((needed == 2 ? 0xc0 : 0x80) | ((value >> 6) & 0x3f));
            output[target++] = (unsigned char) (0x80 | (value & 0x3f));
        }
    }
    *written = target;
    return NARFS_UTF_OK;
}

narfs_utf_status narfs_utf8_to_utf16(
        const unsigned char *input, size_t length,
        uint16_t *output, size_t capacity, size_t *written) {
    size_t source = 0, target = 0;
    if (input == NULL || output == NULL || written == NULL) return NARFS_UTF_INVALID;
    while (source < length) {
        unsigned char first = input[source++];
        uint32_t value;
        size_t extra, index;
        if (first >= 1 && first <= 0x7f) {
            value = first;
            extra = 0;
        } else if (first >= 0xc2 && first <= 0xdf) {
            value = first & 0x1f;
            extra = 1;
        } else if (first >= 0xe0 && first <= 0xef) {
            value = first & 0x0f;
            extra = 2;
        } else if (first >= 0xf0 && first <= 0xf4) {
            value = first & 0x07;
            extra = 3;
        } else return NARFS_UTF_INVALID;
        if (source + extra > length) return NARFS_UTF_INVALID;
        if (extra > 0 && ((first == 0xe0 && input[source] < 0xa0)
                || (first == 0xed && input[source] > 0x9f)
                || (first == 0xf0 && input[source] < 0x90)
                || (first == 0xf4 && input[source] > 0x8f))) {
            return NARFS_UTF_INVALID;
        }
        for (index = 0; index < extra; index++) {
            unsigned char next = input[source++];
            if ((next & 0xc0) != 0x80) return NARFS_UTF_INVALID;
            value = (value << 6) | (next & 0x3f);
        }
        if (value <= 0xffff) {
            if (target == capacity) return NARFS_UTF_LIMIT;
            output[target++] = (uint16_t) value;
        } else {
            if (target + 2 > capacity) return NARFS_UTF_LIMIT;
            value -= 0x10000;
            output[target++] = (uint16_t) (0xd800 | (value >> 10));
            output[target++] = (uint16_t) (0xdc00 | (value & 0x3ff));
        }
    }
    *written = target;
    return NARFS_UTF_OK;
}
