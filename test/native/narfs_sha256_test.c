#include "../../jni/narfs/narfs_sha256.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(value) do { \
    if (!(value)) { \
        fprintf(stderr, "check failed at %s:%d: %s\n", \
                __FILE__, __LINE__, #value); \
        exit(1); \
    } \
} while (0)

static void decode(
        const char *hex, unsigned char expected[NARFS_SHA256_BYTES]) {
    unsigned index, value;
    for (index = 0; index < NARFS_SHA256_BYTES; index++) {
        CHECK(sscanf(hex + index * 2, "%2x", &value) == 1);
        expected[index] = (unsigned char) value;
    }
}

static void check_digest(
        const unsigned char *bytes, size_t count, size_t chunk,
        const char *hex) {
    narfs_sha256 context;
    unsigned char actual[NARFS_SHA256_BYTES];
    unsigned char expected[NARFS_SHA256_BYTES];
    size_t offset = 0;
    narfs_sha256_init(&context);
    while (offset < count) {
        size_t take = count - offset < chunk ? count - offset : chunk;
        narfs_sha256_update(&context, bytes + offset, take);
        offset += take;
    }
    narfs_sha256_final(&context, actual);
    decode(hex, expected);
    CHECK(memcmp(actual, expected, sizeof(actual)) == 0);
}

int main(void) {
    static const unsigned char abc[] = "abc";
    static const char empty_hash[] =
            "e3b0c44298fc1c149afbf4c8996fb924"
            "27ae41e4649b934ca495991b7852b855";
    static const char abc_hash[] =
            "ba7816bf8f01cfea414140de5dae2223"
            "b00361a396177a9cb410ff61f20015ad";
    static const char thousand_a_hash[] =
            "41edece42d63e8d9bf515a9ba6932e1"
            "c20cbc9f5a5d134645adb5db1b9737ea3";
    unsigned char thousand_a[1000];
    narfs_sha256 reusable;
    unsigned char first[NARFS_SHA256_BYTES], second[NARFS_SHA256_BYTES];

    memset(thousand_a, 'a', sizeof(thousand_a));
    check_digest(NULL, 0, 1, empty_hash);
    check_digest(abc, 3, 3, abc_hash);
    check_digest(thousand_a, sizeof(thousand_a), 1, thousand_a_hash);
    check_digest(thousand_a, sizeof(thousand_a), 63, thousand_a_hash);
    check_digest(thousand_a, sizeof(thousand_a), 64, thousand_a_hash);
    check_digest(thousand_a, sizeof(thousand_a), 65, thousand_a_hash);

    narfs_sha256_init(&reusable);
    narfs_sha256_update(&reusable, abc, 3);
    narfs_sha256_final(&reusable, first);
    narfs_sha256_init(&reusable);
    narfs_sha256_update(&reusable, abc, 1);
    narfs_sha256_update(&reusable, abc + 1, 2);
    narfs_sha256_final(&reusable, second);
    CHECK(memcmp(first, second, sizeof(first)) == 0);
    puts("narfs sha256 host tests passed");
    return 0;
}
