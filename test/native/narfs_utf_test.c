#include "../../jni/narfs/narfs_utf.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { \
    fprintf(stderr, "UTF check failed at %d: %s\n", __LINE__, #x); return 1; \
} } while (0)

static int utf16_cases(void) {
    const uint16_t ascii[] = {'a', '/', 'z'};
    const uint16_t supplementary[] = {'x', 0xd83d, 0xde00};
    const uint16_t nul[] = {'a', 0};
    const uint16_t high[] = {0xd800};
    const uint16_t low[] = {0xdc00};
    unsigned char output[16];
    size_t length = 0;
    CHECK(narfs_utf16_to_utf8(ascii, 3, output, sizeof(output), &length) == NARFS_UTF_OK);
    CHECK(length == 3 && memcmp(output, "a/z", 3) == 0);
    CHECK(narfs_utf16_to_utf8(supplementary, 3, output, sizeof(output), &length) == NARFS_UTF_OK);
    CHECK(length == 5 && memcmp(output, "x\xf0\x9f\x98\x80", 5) == 0);
    CHECK(narfs_utf16_to_utf8(nul, 2, output, sizeof(output), &length) == NARFS_UTF_INVALID);
    CHECK(narfs_utf16_to_utf8(high, 1, output, sizeof(output), &length) == NARFS_UTF_INVALID);
    CHECK(narfs_utf16_to_utf8(low, 1, output, sizeof(output), &length) == NARFS_UTF_INVALID);
    CHECK(narfs_utf16_to_utf8(ascii, 3, output, 2, &length) == NARFS_UTF_LIMIT);
    return 0;
}

static int utf8_cases(void) {
    const unsigned char supplementary[] = "x\xf0\x9f\x98\x80";
    const unsigned char *invalid[] = {
        (const unsigned char *) "\xc0\x80", (const unsigned char *) "\xed\xa0\x80",
        (const unsigned char *) "\xf4\x90\x80\x80", (const unsigned char *) "\xe2\x82",
        (const unsigned char *) "\x80", (const unsigned char *) "a\0b"
    };
    const size_t lengths[] = {2, 3, 4, 2, 1, 3};
    uint16_t output[8];
    size_t length = 0;
    size_t index;
    CHECK(narfs_utf8_to_utf16(supplementary, 5, output, 8, &length) == NARFS_UTF_OK);
    CHECK(length == 3 && output[0] == 'x' && output[1] == 0xd83d && output[2] == 0xde00);
    for (index = 0; index < sizeof(invalid) / sizeof(invalid[0]); index++) {
        CHECK(narfs_utf8_to_utf16(invalid[index], lengths[index], output, 8, &length)
                == NARFS_UTF_INVALID);
    }
    CHECK(narfs_utf8_to_utf16(supplementary, 5, output, 2, &length) == NARFS_UTF_LIMIT);
    return 0;
}

int main(void) {
    CHECK(utf16_cases() == 0);
    CHECK(utf8_cases() == 0);
    puts("narfs UTF tests passed");
    return 0;
}
