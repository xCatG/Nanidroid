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
    narfs_stage_test_operation primary, secondary, cleanup;
    int primary_error, mutate, fired, secondary_fired;
    char source[4096], held[4102], replacement[4096];
} fault;
static int remove_item( const char *path, const struct stat *status, int type, struct FTW *walk) {
    (void) status; (void) type; (void) walk;
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
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY, 0600); CHECK(fd >= 0); CHECK(write(fd, bytes, count) == (ssize_t) count); CHECK(close(fd) == 0);
}

static int hook( narfs_stage_test_operation operation, const char *relative_path, void *context) {
    fault *value = (fault *) context;
    if (value == NULL) return 0;
    if (operation == value->primary && !value->fired) {
        static const unsigned char replacement[] = {9, 8, 7, 6};
        if (value->mutate == 3 && relative_path[0] != 's') return 0;
        value->fired = 1;
        if (value->mutate == 2) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source); CHECK(rename(value->source, value->held) == 0);
            write_file(value->source, replacement, sizeof(replacement));
            return 0;
        }
        if (value->mutate == 3) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source); CHECK(rename(value->source, value->held) == 0);
            make_dir(value->source);
            return 0;
        }
        if (value->mutate == 4) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source); CHECK(rename(value->source, value->held) == 0);
            CHECK(mkfifo(value->source, 0600) == 0);
            return 0;
        }
        if (value->mutate == 5) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source); CHECK(rename(value->source, value->held) == 0);
            make_dir(value->source);
            return 0;
        }
        if (value->mutate == 7) {
            CHECK(snprintf(value->replacement, sizeof(value->replacement),
                    "%s/%s", value->source, relative_path)
                    < (int) sizeof(value->replacement));
            CHECK(snprintf(value->held, sizeof(value->held), "%s.held",
                    value->replacement) < (int) sizeof(value->held));
            CHECK(rename(value->replacement, value->held) == 0);
            CHECK(mkfifo(value->replacement, 0600) == 0);
        }
        if (value->mutate == 8) {
            CHECK(snprintf(value->replacement, sizeof(value->replacement),
                    "%s/%s", value->source, relative_path)
                    < (int) sizeof(value->replacement));
            CHECK(snprintf(value->held, sizeof(value->held), "%s.held",
                    value->replacement) < (int) sizeof(value->held));
            CHECK(rename(value->replacement, value->held) == 0);
            make_dir(value->replacement);
            return 0;
        }
        if (value->mutate == 9 || value->mutate == 10) {
            CHECK(snprintf(value->replacement, sizeof(value->replacement),
                    "%s/%s/b%06u", value->source, relative_path,
                    value->mutate == 9 ? 17U : 999U)
                    < (int) sizeof(value->replacement));
            if (value->mutate == 9) CHECK(unlink(value->replacement) == 0);
            write_file(value->replacement, replacement, sizeof(replacement));
            return 0;
        }
        if (value->mutate == 11) {
            snprintf(value->held, sizeof(value->held), "%s.held", value->source);
            CHECK(rename(value->source, value->held) == 0);
            make_dir(value->source);
        }
        if (value->mutate == 1) {
            int fd = open(value->source, O_WRONLY | O_APPEND); CHECK(fd >= 0); CHECK(write(fd, "x", 1) == 1); CHECK(close(fd) == 0);
            return 0;
        }
        return value->primary_error == 0 ? EIO : value->primary_error;
    }
    if (operation == value->secondary && value->fired
            && !value->secondary_fired) {
        value->secondary_fired = 1;
        if (value->mutate == 6) {
            CHECK(snprintf(value->replacement, sizeof(value->replacement),
                    "%s/%s", value->source, relative_path)
                    < (int) sizeof(value->replacement));
            CHECK(snprintf(value->held, sizeof(value->held), "%s.held",
                    value->replacement) < (int) sizeof(value->held));
            CHECK(rename(value->replacement, value->held) == 0);
            make_dir(value->replacement);
        }
        return 0;
    }
    if (operation == value->cleanup
            && (value->primary == 0 || value->fired)) return EIO;
    return 0;
}

static int child_count(const char *path) {
    DIR *directory = opendir(path); struct dirent *entry; int count = 0; CHECK(directory != NULL);
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) count++;
    }
    CHECK(closedir(directory) == 0);
    return count;
}

static void check_blob( const char *staging, const narfs_stage_result *result, const unsigned char *expected, size_t count) {
    char path[4096]; unsigned char observed[32]; int fd;
    snprintf(path, sizeof(path), "%s/%s/b%06u", staging, result->token.session_name, result->entries[2].blob_ordinal);
    fd = open(path, O_RDONLY | O_NOFOLLOW); CHECK(fd >= 0); CHECK(read(fd, observed, sizeof(observed)) == (ssize_t) count);
    CHECK(memcmp(observed, expected, count) == 0); CHECK(close(fd) == 0);
}

static void check_token_blob( const char *staging, const narfs_stage_token *token, uint32_t ordinal, const unsigned char *expected, size_t count) {
    char path[4096]; unsigned char observed[32]; int fd;
    snprintf(path, sizeof(path), "%s/%s/b%06u", staging, token->session_name, ordinal);
    fd = open(path, O_RDONLY | O_NOFOLLOW); CHECK(fd >= 0); CHECK(read(fd, observed, sizeof(observed)) == (ssize_t) count);
    CHECK(memcmp(observed, expected, count) == 0); CHECK(close(fd) == 0);
}

static narfs_stage_result stage( const char *source, const char *target, const char *staging, narfs_stage_options *options) {
    return narfs_stage_existing( source, target, staging, options);
}

static void test_snapshot_and_absent( const char *source, const char *staging) {
    static const unsigned char bytes[] = {0, 1, 254, 255};
    static const unsigned char digest[32] = {
        0xc5, 0xdb, 0xae, 0x22, 0x66, 0x1a, 0xf6, 0xdb, 0x18, 0xa1, 0xf6, 0x76, 0xdb, 0x82, 0xa7, 0xef, 0x7d, 0xe4, 0x6d, 0x27, 0xc3, 0xa2, 0x63, 0xa8, 0x72, 0xf0, 0x04, 0x78, 0xb0, 0xd9, 0x9f, 0xc4
    }; char path[2048]; narfs_stage_options options = narfs_default_stage_options(); narfs_stage_result result; fault injected; options.io_chunk = 2;

    result = stage(source, "missing", staging, &options); CHECK(result.inspected.state == NARFS_STATE_ABSENT);
    CHECK(result.token.session_name[0] == '\0'); narfs_stage_result_dispose(&result);

    snprintf(path, sizeof(path), "%s/empty-target", source); make_dir(path); result = stage(source, "empty-target", staging, &options);
    CHECK(result.inspected.state == NARFS_STATE_PRESENT); CHECK(result.entry_count == 0); CHECK(result.token.session_name[0] != '\0');
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK); narfs_stage_result_dispose(&result);

    snprintf(path, sizeof(path), "%s/ghost", source); make_dir(path); snprintf(path, sizeof(path), "%s/ghost/a-empty", source); make_dir(path);
    snprintf(path, sizeof(path), "%s/ghost/b-\xe9\x9b\xaa", source); make_dir(path);
    snprintf(path, sizeof(path), "%s/ghost/b-\xe9\x9b\xaa/f.bin", source); write_file(path, bytes, sizeof(bytes));
    result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_OK); CHECK(result.entry_count == 3);
    CHECK(strcmp(result.entries[0].relative_path, "a-empty") == 0); CHECK(result.entries[0].blob_ordinal == UINT32_MAX);
    CHECK(strcmp(result.entries[2].relative_path, "b-\xe9\x9b\xaa/f.bin") == 0); CHECK(result.entries[2].blob_ordinal == 0);
    CHECK(memcmp(result.entries[2].sha256, digest, 32) == 0); check_blob(staging, &result, bytes, sizeof(bytes)); result.token.root_inode++;
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_ERR_TREE_CHANGED); result.token.root_inode--; result.token.stage_inode++;
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_ERR_TREE_CHANGED); result.token.stage_inode--;
    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_UNLINK; injected.mutate = 3;
    snprintf(injected.source, sizeof(injected.source), "%s/%s",
            staging, result.token.session_name);
    options.test_hook = hook; options.test_context = &injected;
    CHECK(narfs_stage_discard(staging, &result.token, &options)
            == NARFS_ERR_TREE_CHANGED);
    CHECK(access(injected.source, F_OK) == 0); CHECK(rmdir(injected.source) == 0); CHECK(rename(injected.held, injected.source) == 0);
    options = narfs_default_stage_options(); CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK);
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK); narfs_stage_result_dispose(&result);
}

static void test_faults( const char *source, const char *staging) {
    static const unsigned char original_digest[32] = {
        0xc5,0xdb,0xae,0x22,0x66,0x1a,0xf6,0xdb, 0x18,0xa1,0xf6,0x76,0xdb,0x82,0xa7,0xef, 0x7d,0xe4,0x6d,0x27,0xc3,0xa2,0x63,0xa8, 0x72,0xf0,0x04,0x78,0xb0,0xd9,0x9f,0xc4
    }; narfs_stage_options options = narfs_default_stage_options(); narfs_stage_result result; fault injected; char path[2048]; int operation;
    snprintf(path, sizeof(path), "%s/ghost/b-\xe9\x9b\xaa/f.bin", source);
    for (operation = NARFS_STAGE_TEST_READ;
            operation <= NARFS_STAGE_TEST_SYNC; operation++) {
        memset(&injected, 0, sizeof(injected)); injected.primary = (narfs_stage_test_operation) operation; options.test_hook = hook;
        options.test_context = &injected; result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_IO);
        CHECK(child_count(staging) == 0); narfs_stage_result_dispose(&result);
    }
    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_READ; injected.mutate = 2;
    snprintf(injected.source, sizeof(injected.source), "%s", path); options.test_context = &injected;
    result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_TREE_CHANGED); CHECK(result.entry_count == 3);
    CHECK(memcmp(result.entries[2].sha256, original_digest, 32) == 0); narfs_stage_result_dispose(&result); CHECK(remove(injected.source) == 0);
    CHECK(rename(injected.held, injected.source) == 0);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_READ; injected.mutate = 1;
    snprintf(injected.source, sizeof(injected.source), "%s", path); options.test_context = &injected;
    result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_TREE_CHANGED); CHECK(child_count(staging) == 0);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_WRITE; injected.cleanup = NARFS_STAGE_TEST_CLOSE;
    options.test_context = &injected; result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_IO);
    CHECK(result.inspected.cleanup_error == NARFS_ERR_CLOSE); narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_WRITE; injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    options.test_context = &injected; result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_IO);
    CHECK(result.inspected.cleanup_error == NARFS_ERR_IO); CHECK(narfs_stage_discard(staging, &result.token, NULL) == NARFS_OK);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_OPEN_SESSION; injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    options.test_context = &injected; result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_IO);
    CHECK(result.inspected.cleanup_error == NARFS_ERR_IO); options = narfs_default_stage_options();
    CHECK(narfs_stage_discard(staging, &result.token, &options) == NARFS_OK); narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_CLOSE; options.test_hook = hook; options.test_context = &injected;
    result = stage(source, "ghost", staging, &options); CHECK(result.inspected.error == NARFS_ERR_CLOSE); CHECK(child_count(staging) == 0);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected)); injected.cleanup = NARFS_STAGE_TEST_UNLINK; options.test_context = &injected;
    result = stage(source, "missing", staging, &options); CHECK(result.inspected.state == NARFS_STATE_ERROR);
    CHECK(result.inspected.error == NARFS_ERR_IO); CHECK(narfs_stage_discard(staging, &result.token, NULL) == NARFS_OK);
    narfs_stage_result_dispose(&result);

    options = narfs_default_stage_options(); result = stage(source, "ghost", source, &options);
    CHECK(result.inspected.error == NARFS_ERR_INVALID_OPTIONS); narfs_stage_result_dispose(&result); snprintf(path, sizeof(path), "%s/ghost", source);
    result = stage(source, "ghost", path, &options); CHECK(result.inspected.error == NARFS_ERR_INVALID_OPTIONS); narfs_stage_result_dispose(&result);
}

static void test_eintr_and_missing_close( const char *source, const char *staging) {
    static const narfs_stage_test_operation retried[] = {
        NARFS_STAGE_TEST_READ, NARFS_STAGE_TEST_WRITE,
        NARFS_STAGE_TEST_SYNC, NARFS_STAGE_TEST_CLOSE,
        NARFS_STAGE_TEST_OPEN_SESSION
    };
    narfs_stage_options options = narfs_default_stage_options(); narfs_stage_result result; narfs_stage_token token; fault injected; int operation;
    for (operation = 0; operation < (int) (sizeof(retried) / sizeof(retried[0])); operation++) {
        memset(&injected, 0, sizeof(injected)); injected.primary = retried[operation]; injected.primary_error = EINTR;
        options.test_hook = hook; options.test_context = &injected; result = stage(source, "ghost", staging, &options); CHECK(injected.fired);
        CHECK(result.inspected.error == NARFS_OK); CHECK(narfs_stage_discard(staging, &result.token, NULL) == NARFS_OK);
        narfs_stage_result_dispose(&result);
    }
    options = narfs_default_stage_options(); memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_UNLINK;
    injected.primary_error = EINTR; options.test_hook = hook; options.test_context = &injected; result = stage(source, "ghost", staging, &options);
    token = result.token; CHECK(narfs_stage_discard(staging, &token, &options) == NARFS_OK); CHECK(injected.fired);
    narfs_stage_result_dispose(&result);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_CLOSE; options.test_hook = hook; options.test_context = &injected;
    CHECK(narfs_stage_discard(staging, &token, &options) == NARFS_ERR_CLOSE);
}

static void test_retained_clone( const char *source, const char *staging) {
    static const unsigned char bytes[] = {0, 1, 254, 255};
    narfs_stage_options options = narfs_default_stage_options();
    narfs_stage_result retained;
    narfs_stage_clone_mapping mapping;
    narfs_stage_clone_result candidate;
    fault injected;
    char source_blob[4096], held[4102], live[4096];
    int fd;

    snprintf(live, sizeof(live), "%s/ghost/b-\xe9\x9b\xaa/f.bin", source);
    fd = open(live, O_WRONLY | O_TRUNC); CHECK(fd >= 0);
    CHECK(write(fd, bytes, sizeof(bytes)) == (ssize_t) sizeof(bytes));
    CHECK(close(fd) == 0);
    retained = stage(source, "ghost", staging, &options);
    CHECK(retained.inspected.error == NARFS_OK && retained.entry_count == 3);
    memset(&mapping, 0, sizeof(mapping));
    mapping.retained_blob_ordinal = retained.entries[2].blob_ordinal;
    mapping.candidate_blob_ordinal = 17;
    mapping.expected_size = retained.entries[2].size;
    memcpy(mapping.expected_sha256, retained.entries[2].sha256, 32);

    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_OK && candidate.cleanup_error == NARFS_OK);
    CHECK(strcmp(candidate.token.session_name, retained.token.session_name) != 0);
    check_token_blob(staging, &candidate.token, 17, bytes, sizeof(bytes));
    CHECK(narfs_stage_discard(staging, &candidate.token, &options) == NARFS_OK);

    candidate = narfs_stage_clone_retained(staging, &retained.token, NULL, 0, &options);
    CHECK(candidate.error == NARFS_OK); CHECK(narfs_stage_discard(staging, &candidate.token, &options) == NARFS_OK);
    {
        narfs_stage_clone_mapping pair[2];
        pair[0] = mapping; pair[1] = mapping; pair[1].candidate_blob_ordinal = 999;
        candidate = narfs_stage_clone_retained(staging, &retained.token, pair, 2, &options);
        CHECK(candidate.error == NARFS_ERR_INVALID_OPTIONS && candidate.token.session_name[0] == '\0');
        pair[1] = mapping; pair[1].retained_blob_ordinal = mapping.retained_blob_ordinal + 1;
        candidate = narfs_stage_clone_retained(staging, &retained.token, pair, 2, &options);
        CHECK(candidate.error == NARFS_ERR_INVALID_OPTIONS && candidate.token.session_name[0] == '\0');
    }
    {
        narfs_stage_clone_mapping pair[2];
        char second[4096];
        snprintf(second, sizeof(second), "%s/%s/b000001", staging, retained.token.session_name);
        write_file(second, bytes, sizeof(bytes));
        pair[0] = mapping; pair[1] = mapping;
        pair[1].retained_blob_ordinal = 1; pair[1].candidate_blob_ordinal = 999;
        candidate = narfs_stage_clone_retained(staging, &retained.token, pair, 2, &options);
        CHECK(candidate.error == NARFS_OK);
        check_token_blob(staging, &candidate.token, 17, bytes, sizeof(bytes));
        check_token_blob(staging, &candidate.token, 999, bytes, sizeof(bytes));
        CHECK(narfs_stage_discard(staging, &candidate.token, &options) == NARFS_OK);
    }
    options.inspect.max_total_bytes = 3;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TOTAL_SIZE_LIMIT && candidate.token.session_name[0] == '\0');
    options = narfs_default_stage_options();

    mapping.candidate_blob_ordinal = NARFS_STAGE_MAX_BLOB_ORDINAL + 1U;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_INVALID_OPTIONS && candidate.token.session_name[0] == '\0');
    mapping.candidate_blob_ordinal = 17;
    mapping.expected_sha256[0] ^= 1;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED && candidate.token.session_name[0] == '\0');
    mapping.expected_sha256[0] ^= 1;
    retained.token.stage_inode++;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED && candidate.token.session_name[0] == '\0');
    retained.token.stage_inode--;

    snprintf(source_blob, sizeof(source_blob), "%s/%s/b%06u", staging,
            retained.token.session_name, mapping.retained_blob_ordinal);
    snprintf(held, sizeof(held), "%s.held", source_blob);
    CHECK(rename(source_blob, held) == 0); CHECK(symlink(held, source_blob) == 0);
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED && candidate.token.session_name[0] == '\0');
    CHECK(unlink(source_blob) == 0); CHECK(rename(held, source_blob) == 0);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_READ;
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO && candidate.token.session_name[0] == '\0');
    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_SYNC;
    options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO && candidate.token.session_name[0] == '\0');
    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_CLOSE;
    options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_CLOSE && candidate.token.session_name[0] == '\0');

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_BEGIN_COPY; injected.mutate = 4;
    snprintf(injected.source, sizeof(injected.source), "%s", source_blob);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED && candidate.token.session_name[0] == '\0');
    CHECK(unlink(source_blob) == 0); CHECK(rename(injected.held, source_blob) == 0);

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_CREATE_SESSION; injected.mutate = 5;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED && candidate.token.session_name[0] == '\0');
    CHECK(child_count(staging) == 0); CHECK(rmdir(staging) == 0); CHECK(rename(injected.held, staging) == 0);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_STAT_SESSION;
    injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO && candidate.cleanup_error == NARFS_ERR_IO);
    CHECK(candidate.token.session_name[0] != '\0');
    CHECK(candidate.token.stage_device != 0 && candidate.token.stage_inode != 0);
    options = narfs_default_stage_options();
    CHECK(narfs_stage_discard(staging, &candidate.token, &options) == NARFS_OK);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_STAT_SESSION;
    injected.secondary = NARFS_STAGE_TEST_STAT_SESSION;
    injected.mutate = 6;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO && candidate.cleanup_error == NARFS_OK);
    CHECK(candidate.token.session_name[0] == '\0');
    CHECK(access(injected.replacement, F_OK) == 0);
    CHECK(access(injected.held, F_OK) == 0);
    CHECK(rmdir(injected.replacement) == 0);
    clear_tree(injected.held);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_STAT_SESSION;
    injected.mutate = 7;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO && candidate.cleanup_error == NARFS_OK);
    CHECK(candidate.token.session_name[0] == '\0');
    CHECK(access(injected.replacement, F_OK) == 0);
    CHECK(access(injected.held, F_OK) == 0);
    CHECK(unlink(injected.replacement) == 0);
    CHECK(rmdir(injected.held) == 0);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_BEGIN_COPY;
    injected.mutate = 5;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED);
    CHECK(candidate.token.session_name[0] == '\0');
    CHECK(child_count(staging) == 0);
    CHECK(rmdir(staging) == 0);
    CHECK(rename(injected.held, staging) == 0);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_COMPLETE_COPY;
    injected.mutate = 8;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED);
    CHECK(candidate.cleanup_error == NARFS_ERR_TREE_CHANGED);
    CHECK(candidate.token.session_name[0] == '\0');
    CHECK(access(injected.replacement, F_OK) == 0);
    CHECK(access(injected.held, F_OK) == 0);
    CHECK(rmdir(injected.replacement) == 0);
    clear_tree(injected.held);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_COMPLETE_COPY;
    injected.mutate = 9;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED);
    CHECK(candidate.token.session_name[0] == '\0');

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_COMPLETE_COPY;
    injected.mutate = 10;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_TREE_CHANGED);
    CHECK(candidate.token.session_name[0] == '\0');

    memset(&injected, 0, sizeof(injected)); injected.primary = NARFS_STAGE_TEST_WRITE; injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO && candidate.cleanup_error == NARFS_ERR_IO);
    CHECK(candidate.token.session_name[0] != '\0');
    options = narfs_default_stage_options();
    CHECK(narfs_stage_discard(staging, &candidate.token, &options) == NARFS_OK);

    memset(&injected, 0, sizeof(injected));
    injected.primary = NARFS_STAGE_TEST_WRITE;
    injected.cleanup = NARFS_STAGE_TEST_UNLINK;
    injected.mutate = 11;
    snprintf(injected.source, sizeof(injected.source), "%s", staging);
    options.test_hook = hook; options.test_context = &injected;
    candidate = narfs_stage_clone_retained(staging, &retained.token, &mapping, 1, &options);
    CHECK(candidate.error == NARFS_ERR_IO);
    CHECK(candidate.token.session_name[0] == '\0');
    CHECK(rmdir(staging) == 0);
    CHECK(rename(injected.held, staging) == 0);
    clear_tree(staging);
    narfs_stage_result_dispose(&retained);
}

int main(void) {
    char root[] = "/tmp/narfs-stage-test-XXXXXX"; char source[2048], staging[2048]; CHECK(mkdtemp(root) != NULL);
    snprintf(source, sizeof(source), "%s/source", root); snprintf(staging, sizeof(staging), "%s/staging", root); make_dir(source); make_dir(staging);
    test_snapshot_and_absent(source, staging); test_faults(source, staging); test_eintr_and_missing_close(source, staging); test_retained_clone(source, staging); clear_tree(root);
    puts("narfs stage host tests passed");
    return 0;
}
