#include "narfs_sha256.h"

#include <string.h>

static const uint32_t constants[64] = {
    0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,
    0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
    0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,
    0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
    0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,
    0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
    0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,
    0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
    0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,
    0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
    0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,
    0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
    0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,
    0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
    0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,
    0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U
};

static uint32_t rotate(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

static void transform(narfs_sha256 *context) {
    uint32_t words[64], a, b, c, d, e, f, g, h, first, second;
    unsigned index;
    for (index = 0; index < 16; index++) {
        const unsigned char *byte = context->block + index * 4U;
        words[index] = (uint32_t) byte[0] << 24
                | (uint32_t) byte[1] << 16
                | (uint32_t) byte[2] << 8 | byte[3];
    }
    for (; index < 64; index++) {
        uint32_t x = words[index - 15], y = words[index - 2];
        words[index] = words[index - 16]
                + (rotate(x, 7) ^ rotate(x, 18) ^ (x >> 3))
                + words[index - 7]
                + (rotate(y, 17) ^ rotate(y, 19) ^ (y >> 10));
    }
    a=context->state[0]; b=context->state[1]; c=context->state[2];
    d=context->state[3]; e=context->state[4]; f=context->state[5];
    g=context->state[6]; h=context->state[7];
    for (index = 0; index < 64; index++) {
        first = h + (rotate(e,6)^rotate(e,11)^rotate(e,25))
                + ((e&f)^((~e)&g)) + constants[index] + words[index];
        second = (rotate(a,2)^rotate(a,13)^rotate(a,22))
                + ((a&b)^(a&c)^(b&c));
        h=g; g=f; f=e; e=d+first; d=c; c=b; b=a; a=first+second;
    }
    context->state[0]+=a; context->state[1]+=b;
    context->state[2]+=c; context->state[3]+=d;
    context->state[4]+=e; context->state[5]+=f;
    context->state[6]+=g; context->state[7]+=h;
}

void narfs_sha256_init(narfs_sha256 *context) {
    static const uint32_t initial[8] = {
        0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,
        0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U
    };
    memset(context, 0, sizeof(*context));
    memcpy(context->state, initial, sizeof(initial));
}

void narfs_sha256_update(
        narfs_sha256 *context, const unsigned char *bytes, size_t count) {
    while (count > 0) {
        size_t room = sizeof(context->block) - context->used;
        size_t take = count < room ? count : room;
        memcpy(context->block + context->used, bytes, take);
        context->used += take;
        bytes += take;
        count -= take;
        context->bits += (uint64_t) take * 8U;
        if (context->used == sizeof(context->block)) {
            transform(context);
            context->used = 0;
        }
    }
}

void narfs_sha256_final(
        narfs_sha256 *context, unsigned char output[NARFS_SHA256_BYTES]) {
    uint64_t bits = context->bits;
    unsigned index;
    context->block[context->used++] = 0x80;
    if (context->used > 56) {
        memset(context->block + context->used, 0, 64 - context->used);
        transform(context);
        context->used = 0;
    }
    memset(context->block + context->used, 0, 56 - context->used);
    for (index = 0; index < 8; index++)
        context->block[63-index] = (unsigned char)(bits >> (index*8U));
    transform(context);
    for (index = 0; index < 8; index++) {
        output[index*4]=(unsigned char)(context->state[index]>>24);
        output[index*4+1]=(unsigned char)(context->state[index]>>16);
        output[index*4+2]=(unsigned char)(context->state[index]>>8);
        output[index*4+3]=(unsigned char)context->state[index];
    }
}
