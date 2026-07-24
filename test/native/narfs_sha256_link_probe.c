#include "narfs_sha256.h"

int main(void) {
    narfs_sha256 context;
    unsigned char digest[NARFS_SHA256_BYTES];
    narfs_sha256_init(&context);
    narfs_sha256_update(&context, 0, 0);
    narfs_sha256_final(&context, digest);
    return digest[0] != 0xe3;
}
