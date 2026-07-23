#ifndef NANIDROID_NARFS_CORE_H
#define NANIDROID_NARFS_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NARFS_MAX_ENTRIES 10000U
#define NARFS_MAX_DEPTH 32U
#define NARFS_MAX_COMPONENT_BYTES 255U
#define NARFS_MAX_PATH_BYTES 1024U
#define NARFS_MAX_FILE_BYTES (128ULL * 1024ULL * 1024ULL)
#define NARFS_MAX_TOTAL_BYTES (512ULL * 1024ULL * 1024ULL)

typedef enum narfs_state {
    NARFS_STATE_ERROR = 0, NARFS_STATE_ABSENT, NARFS_STATE_PRESENT
} narfs_state;
typedef enum narfs_error {
    NARFS_OK = 0, NARFS_ERR_INVALID_OPTIONS,
    NARFS_ERR_INVALID_TARGET, NARFS_ERR_ROOT_TYPE,
    NARFS_ERR_TARGET_TYPE, NARFS_ERR_SYMLINK,
    NARFS_ERR_SPECIAL_TYPE, NARFS_ERR_INVALID_NAME,
    NARFS_ERR_COMPONENT_LIMIT, NARFS_ERR_PATH_LIMIT,
    NARFS_ERR_DEPTH_LIMIT, NARFS_ERR_ENTRY_COUNT_LIMIT,
    NARFS_ERR_FILE_SIZE_LIMIT, NARFS_ERR_TOTAL_SIZE_LIMIT,
    NARFS_ERR_CYCLE, NARFS_ERR_TREE_CHANGED,
    NARFS_ERR_PERMISSION, NARFS_ERR_RESOURCE, NARFS_ERR_IO,
    NARFS_ERR_VISITOR, NARFS_ERR_CLOSE
} narfs_error;
typedef enum narfs_entry_type {
    NARFS_ENTRY_FILE = 1, NARFS_ENTRY_DIRECTORY = 2
} narfs_entry_type;
typedef struct narfs_entry {
    const char *relative_path;
    narfs_entry_type type;
    uint64_t size;
    uint64_t device;
    uint64_t inode;
} narfs_entry;
typedef int (*narfs_visitor)(
        const narfs_entry *entry, int fd, void *context);
typedef enum narfs_test_operation {
    NARFS_TEST_OPEN = 1, NARFS_TEST_FSTATAT, NARFS_TEST_FSTAT,
    NARFS_TEST_READDIR, NARFS_TEST_CLOSE
} narfs_test_operation;
typedef int (*narfs_operation_hook)(
        narfs_test_operation operation,
        const char *relative_path,
        void *context);
typedef struct narfs_options {
    uint32_t max_entries;
    uint32_t max_depth;
    uint32_t max_component_bytes;
    uint32_t max_path_bytes;
    uint64_t max_file_bytes;
    uint64_t max_total_bytes;
    narfs_operation_hook test_hook;
    void *test_context;
} narfs_options;
typedef struct narfs_result {
    narfs_state state;
    narfs_error error;
    narfs_error cleanup_error;
    uint32_t entry_count;
    uint64_t total_file_size;
} narfs_result;

narfs_options narfs_default_options(void);

/*
 * The visitor fd is borrowed and valid only during that callback.
 * The trusted root must already be canonical; target is exactly one validated
 * child component. Returned metadata does not authorize later file copying.
 */
narfs_result narfs_inspect(
        const char *trusted_root,
        const char *target,
        const narfs_options *options,
        narfs_visitor visitor,
        void *visitor_context);

#ifdef __cplusplus
}
#endif

#endif
