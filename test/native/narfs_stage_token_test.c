#include "../../jni/narfs/narfs_stage_token.h"

#include <stdio.h>
#include <string.h>

#define CHECK(value) do { \
    if (!(value)) { \
        fprintf(stderr, "check failed at line %d\n", __LINE__); \
        return 1; \
    } \
} while (0)

static narfs_stage_token token(void) {
    narfs_stage_token value;
    memset(&value, 0, sizeof(value));
    memcpy(value.session_name,
            "s0123456789abcdef0123456789abcdef", 34);
    value.root_device = 7;
    value.root_inode = 11;
    value.stage_device = 13;
    value.stage_inode = 17;
    return value;
}

int main(void) {
    unsigned char encoded[NARFS_STAGE_WIRE_BYTES];
    unsigned char expected[NARFS_STAGE_WIRE_BYTES] = {0};
    unsigned char mutated[NARFS_STAGE_WIRE_BYTES];
    narfs_stage_token original = token(), decoded;
    size_t index;

    CHECK(!narfs_stage_token_encode(
            NULL, encoded, sizeof(encoded)));
    CHECK(!narfs_stage_token_encode(
            &original, NULL, sizeof(encoded)));
    CHECK(!narfs_stage_token_decode(
            NULL, sizeof(encoded), &decoded));
    CHECK(!narfs_stage_token_decode(
            encoded, sizeof(encoded), NULL));
    CHECK(narfs_stage_token_encode(
            &original, encoded, sizeof(encoded)));
    memcpy(expected, "NSTG", 4);
    expected[4] = NARFS_STAGE_WIRE_VERSION;
    expected[8] = 33;
    memcpy(expected + 16, original.session_name, 33);
    expected[63] = 7;
    expected[71] = 11;
    expected[79] = 13;
    expected[87] = 17;
    CHECK(memcmp(encoded, expected, sizeof(expected)) == 0);
    CHECK(narfs_stage_token_decode(
            encoded, sizeof(encoded), &decoded));
    CHECK(memcmp(&original, &decoded, sizeof(original)) == 0);
    CHECK(!narfs_stage_token_encode(
            &original, encoded, sizeof(encoded) - 1));
    CHECK(!narfs_stage_token_decode(
            encoded, sizeof(encoded) - 1, &decoded));

    memcpy(mutated, encoded, sizeof(mutated));
    mutated[0] = 'X';
    CHECK(!narfs_stage_token_decode(mutated, sizeof(mutated), &decoded));
    memcpy(mutated, encoded, sizeof(mutated));
    mutated[8] = 32;
    CHECK(!narfs_stage_token_decode(mutated, sizeof(mutated), &decoded));
    for (index = 0; index < sizeof(encoded); index++) {
        memcpy(mutated, encoded, sizeof(mutated));
        if ((index >= 5 && index <= 7)
                || (index >= 9 && index <= 15)
                || (index >= 49 && index <= 55)) {
            mutated[index] = 1;
            CHECK(!narfs_stage_token_decode(
                    mutated, sizeof(mutated), &decoded));
        }
    }
    memcpy(mutated, encoded, sizeof(mutated));
    mutated[4] = 2;
    memset(&decoded, 0xa5, sizeof(decoded));
    CHECK(!narfs_stage_token_decode(mutated, sizeof(mutated), &decoded));
    CHECK(decoded.session_name[0] == '\0'
            && decoded.root_device == 0 && decoded.root_inode == 0
            && decoded.stage_device == 0 && decoded.stage_inode == 0);
    memcpy(mutated, encoded, sizeof(mutated));
    mutated[16] = 'x';
    CHECK(!narfs_stage_token_decode(mutated, sizeof(mutated), &decoded));
    memcpy(mutated, encoded, sizeof(mutated));
    mutated[17] = 'G';
    memset(&decoded, 0xa5, sizeof(decoded));
    CHECK(!narfs_stage_token_decode(mutated, sizeof(mutated), &decoded));
    CHECK(decoded.session_name[0] == '\0'
            && decoded.root_device == 0 && decoded.root_inode == 0
            && decoded.stage_device == 0 && decoded.stage_inode == 0);
    original = token();
    original.session_name[33] = 'x';
    CHECK(!narfs_stage_token_encode(
            &original, mutated, sizeof(mutated)));
    original = token();
    original.session_name[2] = 'G';
    CHECK(!narfs_stage_token_encode(
            &original, mutated, sizeof(mutated)));
    for (index = 56; index <= 80; index += 8) {
        memcpy(mutated, encoded, sizeof(mutated));
        memset(mutated + index, 0, 8);
        memset(&decoded, 0xa5, sizeof(decoded));
        CHECK(!narfs_stage_token_decode(
                mutated, sizeof(mutated), &decoded));
        CHECK(decoded.session_name[0] == '\0'
                && decoded.root_device == 0 && decoded.root_inode == 0
                && decoded.stage_device == 0 && decoded.stage_inode == 0);
    }
    for (index = 56; index <= 80; index += 8) {
        memcpy(mutated, encoded, sizeof(mutated));
        mutated[index] = 0x80;
        CHECK(!narfs_stage_token_decode(
                mutated, sizeof(mutated), &decoded));
    }

    puts("narfs stage token host tests passed");
    return 0;
}
