#define _XOPEN_SOURCE 700
#include "../../jni/narfs/narfs_stage.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#define CHECK(value) do { if (!(value)) { \
    fprintf(stderr, "check failed at %s:%d: %s\n", \
            __FILE__, __LINE__, #value); exit(1); } } while (0)
typedef struct fault {
    narfs_stage_test_operation primary;
    narfs_stage_test_operation cleanup;
    int primary_error;
    int mutate;
    int fired;
    char source[2048];
    char held[4096];
} fault;
static int remove_item( const char *path, const struct stat *status, int type, struct FTW *walk) {
    (void) status;
    (void) type;
    (void) walk;
    return remove(path);
}

static void clear_tree(const char *path) {
    if (access(path, F_OK) == 0) {
        CHECK(nftw(path, remove_item, 32, FTW_DEPTH | FTW_PHYS) == 0);
    }
}

static void make_dir(const char *path) {
    CHECK(mkdir(path, 0700) == 0);
}

static void write_file( const char *path, const unsigned char *bytes, size_t count) {
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY, 0600);
    CHECK(fd >= 0);
    CHECK(write(fd, bytes, count) == (ssize_t) count);
    CHECK(close(fd) == 0);
}

static int hook( narfs_stage_test_operation operation, const char *relative_path, void *context) {
    fault *value = (fault *) context;
    if (value == NULL) return 0;
    if (operation == value->primary && !value->fired) {
        static const unsigned char replacement[] = {9, 8, 7, 6};
        if (value->mutate == 3 && relative_path[0] != 's') return 0;
        value->fired = 1;
        if (value->mutate == 2) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source);
            CHECK(rename(value->source, value->held) == 0);
            write_file(value->source, replacement, sizeof(replacement));
            return 0;
        }
        if (value->mutate == 3) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source);
            CHECK(rename(value->source, value->held) == 0);
            make_dir(value->source);
            return 0;
        }
        if (value->mutate == 1) {
            int fd = open(value->source, O_WRONLY | O_APPEND);
            CHECK(fd >= 0);
            CHECK(write(fd, "x", 1) == 1);
            CHECK(close(fd) == 0);
            return 0;
        }
        return value->primary_error == 0 ? EIO : value->primary_error;
    }
    if (operation == value->cleanup) return EIO;
    return 0;
}

static int child_count(const char *path) {
    DIR *directory = opendir(path);
    struct dirent *entry;
    int count = 0;
    CHECK(directory != NULL);
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) count++;
    }
    CHECK(closedir(directory) == 0);
    return count;
}

static void check_blob( const char *staging, const narfs_stage_result *result, const unsigned char *expected, size_t count) {
    char path[4096];
    unsigned char observed[32];
    int fd;
    snprintf(path, sizeof(path), "%s/%s/b%06u", staging, result->token.session_name, result->entries[2].blob_ordinal);
    fd = open(path, O_RDONLY | O_NOFOLLOW);
    CHECK(fd >= 0);
    CHECK(read(fd, observed, sizeof(observed)) == (ssize_t) count);
    CHECK(memcmp(observed, expected, count) == 0);
    CHECK(close(fd) == 0);
}

static narfs_stage_result stage( const char *source, const char *target, const char *staging, narfs_stage_options *options) {
    return narfs_stage_existing( source, target, staging, options);
}

static void test_snapshot_and_absent( const char *source, const char *staging) {
    static const unsigned char bytes[] = {0, 1, 254, 255};
    static const unsigned char digest[32] = {
        0xc5, 0xdb, 0xae, 0x22, 0x66, 0x1a, 0xf6, 0xdb, 0x18, 0xa1, 0xf6, 0x76, 0xdb, 0x82, 0xa7, 0xef, 0x7d, 0xe4, 0x6d, 0x27, 0xc3, 0xa2, 0x63, 0xa8, 0x72, 0xf0, 0x04, 0x78, 0xb0, 0xd9, 0x9f, 0xc4
    };
    char path[2048];
    narfs_stage_options options = narfs_default_stage_options();
    narfs_stage_result result;
    fault injected;
    options.io_chunk = 2;

    result = stage(source, "missing", staging, &options);
    CHECK(result.inspected.state == NARFS_STATE_ABSENT);
    CHECK(result.token.session_name[0] == '\0');
    narfs_stage_result_dispose(&result);

    snprintf(path, sizeof(path), "%s/empty-target", source);
    make_dir(path);
    result = stage(source, "empty-target", staging, &options);
    CHECK(result.inspected.state == NARFS_STATE_PRESENT);
    CHECK(result.entry_count == 0);
    CHECK(result.token.session_name[0] != '\0');
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK);
    narfs_stage_result_dispose(&result);

    snprintf(path, sizeof(path), "%s/ghost", source);
    make_dir(path);
    snprintf(path, sizeof(path), "%s/ghost/a-empty", source);
    make_dir(path);
    snprintf(path, sizeof(path), "%s/ghost/b-\xe9\x9b\xaa", source);
    make_dir(path);
    snprintf(path, sizeof(path), "%s/ghost/b-\xe9\x9b\xaa/f.bin", source);
    write_file(path, bytes, sizeof(bytes));
    result = stage(source, "ghost", staging, &options);
    CHECK(result.inspected.error == NARFS_OK);
    CHECK(result.entry_count == 3);
    CHECK(strcmp(result.entries[0].relative_path, "a-empty") == 0);
    CHECK(result.entries[0].blob_ordinal == UINT32_MAX);
    CHECK(strcmp(result.entries[2].relative_path, "b-\xe9\x9b\xaa/f.bin") == 0);
    CHECK(result.entries[2].blob_ordinal == 0);
    CHECK(memcmp(result.entries[2].sha256, digest, 32) == 0);
    check_blob(staging, &result, bytes, sizeof(bytes));
    result.token.root_inode++;
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_ERR_TREE_CHANGED);
    result.token.root_inode--;
    result.token.stage_inode++;
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_ERR_TREE_CHANGED);
    result.token.stage_inode--;
    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_UNLINK;
    injected.mutate = 3;
    snprintf(injected.source, sizeof(injected.source), "%s/%s",
            staging, result.token.session_name);
    options.test_hook = hook;
    options.test_context = &injected;
    CHECK(narfs_stage_discard(staging, &result.token, &options)
            == NARFS_ERR_TREE_CHANGED);
    CHECK(access(injected.source, F_OK) == 0);
    CHECK(rmdir(injected.source) == 0);
    CHECK(rename(injected.held, injected.source) == 0);
    options = narfs_default_stage_options();
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK);
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK);
    narfs_stage_result_dispose(&result);
}

static void test_faults( const char *source, const char *staging) {
    static const unsigned char original_digest[32] = {
        0xc5,0xdb,0xae,0x22,0x66,0x1a,0xf6,0xdb, 0x18,0xa1,0xf6,0x76,0xdb,0x82,0xa7,0xef, 0x7d,0xe4,0x6d,0x27,0xc3,0xa2,0x63,0xa8, 0x72,0xf0,0x04,0x78,0xb0,0xd9,0x9f,0xc4
    };
    narfs_stage_options options = narfs_default_stage_options();
    narfs_stage_result result;
    fault injected;
    char path[2048];
    int operation;
    snprintf(path, sizeof(path), "%s/ghost/b-\xe9\x9b\xaa/f.bin", source);
    for (operation = NARFS_STAGE_TEST_READ;
            operation <= NARFS_STAGE_TEST_SYNC; operation++) {
        memset(&injected, 0, sizeof(injected));
        injected.primary = (narfs_stage_test_operation) operation;
        options.test_hook = hook;
        options.test_context = &injected;
        result = stage(source, "ghost", staging, &options);
        CHECK(result.inspected.error == NARFS_ERR_IO);
        CHECK(child_count(staging) == 0);
        narfs_stage_result_dispose(&result);
    }
    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_READ;
    injected.mutate = 2;
    snprintf(injected.source, sizeof(injected.source), "%s", path);
    options.test_context = &injected;
    result = stage(source, "ghost", staging, &options);
    CHECK(result.inspected.error == NARFS_ERR_TREE_CHANGED);
    CHECK(result.entry_count == 3);
    CHECK(memcmp(result.entries[2].sha256, original_digest, 32) == 0);
    narfs_stage_result_dispose(&result);
    CHECK(remove(injected.source) == 0);
    CHECK(rename(injected.held, injected.source) == 0);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_READ;
    injected.mutate = 1;
    snprintf(injected.source, sizeof(injected.source), "%s", path);
    options.test_context = &injected;
    result = stage(source, "ghost", staging, &options);
    CHECK(result.inspected.error == NARFS_ERR_TREE_CHANGED);
    CHECK(child_count(staging) == 0);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_WRITE;
    injected.cleanup = NARFS_STAGE_TEST_CLOSE;
    options.test_context = &injected;
    result = stage(source, "ghost", staging, &options);
    CHECK(result.inspected.error == NARFS_ERR_IO);
    CHECK(result.inspected.cleanup_error == NARFS_ERR_CLOSE);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_WRITE;
    injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    options.test_context = &injected;
    result = stage(source, "ghost", staging, &options);
    CHECK(result.inspected.error == NARFS_ERR_IO);
    CHECK(result.inspected.cleanup_error == NARFS_ERR_IO);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected));
    injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    options.test_context = &injected;
    result = stage(source, "missing", staging, &options);
    CHECK(result.inspected.state == NARFS_STATE_ERROR);
    CHECK(result.inspected.error == NARFS_ERR_IO);
    narfs_stage_result_dispose(&result);

    options = narfs_default_stage_options();
    result = stage(source, "ghost", source, &options);
    CHECK(result.inspected.error == NARFS_ERR_INVALID_OPTIONS);
    narfs_stage_result_dispose(&result);
    snprintf(path, sizeof(path), "%s/ghost", source);
    result = stage(source, "ghost", path, &options);
    CHECK(result.inspected.error == NARFS_ERR_INVALID_OPTIONS);
    narfs_stage_result_dispose(&result);
}

static void test_eintr_and_missing_close( const char *source, const char *staging) {
    narfs_stage_options options = narfs_default_stage_options();
    narfs_stage_result result;
    narfs_stage_token token;
    fault injected;
    int operation;
    for (operation = NARFS_STAGE_TEST_READ;
            operation <= NARFS_STAGE_TEST_CLOSE; operation++) {
        memset(&injected, 0, sizeof(injected));
        injected.primary = (narfs_stage_test_operation) operation;
        injected.primary_error = EINTR;
        options.test_hook = hook;
        options.test_context = &injected;
        result = stage(source, "ghost", staging, &options);
        CHECK(injected.fired);
        CHECK(result.inspected.error == NARFS_OK);
        CHECK(narfs_stage_discard(staging, &result.token, NULL) == NARFS_OK);
        narfs_stage_result_dispose(&result);
    }
    options = narfs_default_stage_options();
    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_UNLINK;
    injected.primary_error = EINTR;
    options.test_hook = hook;
    options.test_context = &injected;
    result = stage(source, "ghost", staging, &options);
    token = result.token;
    CHECK(narfs_stage_discard(staging, &token, &options) == NARFS_OK);
    CHECK(injected.fired);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_CLOSE;
    options.test_hook = hook;
    options.test_context = &injected;
    CHECK(narfs_stage_discard(staging, &token, &options) == NARFS_ERR_CLOSE);
}

int main(void) {
    char root[] = "/tmp/narfs-stage-test-XXXXXX";
    char source[2048], staging[2048];
    CHECK(mkdtemp(root) != NULL);
    snprintf(source, sizeof(source), "%s/source", root);
    snprintf(staging, sizeof(staging), "%s/staging", root);
    make_dir(source);
    make_dir(staging);
    test_snapshot_and_absent(source, staging);
    test_faults(source, staging);
    test_eintr_and_missing_close(source, staging);
    clear_tree(root);
    puts("narfs stage host tests passed");
    return 0;
}
