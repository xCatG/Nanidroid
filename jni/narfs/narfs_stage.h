#ifndef NANIDROID_NARFS_STAGE_H
#define NANIDROID_NARFS_STAGE_H
#include "narfs_core.h"
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#define NARFS_STAGE_SESSION_BYTES 34U
#define NARFS_STAGE_DEFAULT_IO_CHUNK 16384U
#define NARFS_STAGE_MAX_BLOB_ORDINAL 999999U
typedef enum narfs_stage_test_operation {
    NARFS_STAGE_TEST_READ = 1, NARFS_STAGE_TEST_WRITE, NARFS_STAGE_TEST_SYNC,
    NARFS_STAGE_TEST_CLOSE, NARFS_STAGE_TEST_UNLINK,
    NARFS_STAGE_TEST_OPEN_SESSION
} narfs_stage_test_operation;
typedef int (*narfs_stage_operation_hook)( narfs_stage_test_operation operation, const char *relative_path, void *context);

typedef struct narfs_stage_options {
    narfs_options inspect;
    uint32_t io_chunk;
    narfs_stage_operation_hook test_hook;
    void *test_context;
} narfs_stage_options;

typedef struct narfs_stage_token {
    char session_name[NARFS_STAGE_SESSION_BYTES];
    uint64_t root_device, root_inode;
    uint64_t stage_device, stage_inode;
} narfs_stage_token;

typedef struct narfs_stage_entry {
    char *relative_path;
    narfs_entry_type type;
    uint64_t size;
    uint32_t blob_ordinal;
    unsigned char sha256[32];
} narfs_stage_entry;

typedef struct narfs_stage_result {
    narfs_result inspected;
    narfs_stage_token token;
    narfs_stage_entry *entries;
    uint32_t entry_count;
} narfs_stage_result;

typedef struct narfs_stage_clone_mapping {
    uint32_t retained_blob_ordinal;
    uint32_t candidate_blob_ordinal;
    uint64_t expected_size;
    unsigned char expected_sha256[32];
} narfs_stage_clone_mapping;

typedef struct narfs_stage_clone_result {
    narfs_error error;
    narfs_error cleanup_error;
    narfs_stage_token token;
} narfs_stage_clone_result;

narfs_stage_options narfs_default_stage_options(void);

/*
 * Copies each file from the borrowed fd supplied by narfs_inspect; logical
 * paths are never reopened. A successful PRESENT result owns an opaque,
 * inode-bound staging session which must be passed to narfs_stage_discard.
 *
 * Recovery of sessions orphaned by process-death is deferred to D9b3.
 * Same-size writes which restore timestamps remain outside this slice.
 */
narfs_stage_result narfs_stage_existing( const char *trusted_root, const char *target, const char *staging_root, const narfs_stage_options *options);
narfs_stage_clone_result narfs_stage_clone_retained(
        const char *staging_root, const narfs_stage_token *retained,
        const narfs_stage_clone_mapping *mappings, uint32_t mapping_count,
        const narfs_stage_options *options);
narfs_error narfs_stage_discard( const char *staging_root, const narfs_stage_token *token, const narfs_stage_options *options);
void narfs_stage_result_dispose(narfs_stage_result *result);

#ifdef __cplusplus
}
#endif

#endif
