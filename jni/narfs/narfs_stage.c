#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "narfs_stage.h"
#include "narfs_sha256.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
typedef struct stage_walk {
    int session_fd;
    const narfs_stage_options *options;
    narfs_stage_result *result;
    narfs_error error;
    narfs_error cleanup_error;
} stage_walk;
static narfs_error system_error(int value) {
    if (value == EACCES || value == EPERM) return NARFS_ERR_PERMISSION;
    if (value == ENOMEM || value == EMFILE || value == ENFILE) return NARFS_ERR_RESOURCE;
    return NARFS_ERR_IO;
}

static narfs_error changed_or_system_error(int value) {
    return value == ENOENT || value == ENOTDIR || value == ELOOP ? NARFS_ERR_TREE_CHANGED : system_error(value);
}

static void record_result_error( narfs_stage_result *result, narfs_error error) {
    if (error == NARFS_OK) return;
    if (result->inspected.error == NARFS_OK) {
        result->inspected.error = error;
        result->inspected.state = NARFS_STATE_ERROR;
    } else if (result->inspected.cleanup_error == NARFS_OK) {
        result->inspected.cleanup_error = error;
    }
}

static int injected( const narfs_stage_options *options, narfs_stage_test_operation operation, const char *path) {
    int value = options->test_hook == NULL ? 0 : options->test_hook( operation, path == NULL ? "" : path, options->test_context);
    if (value != 0) errno = value;
    return value == 0 ? 0 : -1;
}

static int close_stage_fd( int fd, const narfs_stage_options *options, const char *path) {
    int result = close(fd);
    if (result == 0) {
        do result = injected(options, NARFS_STAGE_TEST_CLOSE, path);
        while (result != 0 && errno == EINTR);
    }
    return result;
}

static int unlink_stage( int parent, const char *name, int flags, const narfs_stage_options *options, const char *path) {
    int result;
    do {
        result = injected(options, NARFS_STAGE_TEST_UNLINK, path);
        if (result == 0) result = unlinkat(parent, name, flags);
    } while (result != 0 && errno == EINTR);
    return result;
}

static int same_snapshot( const struct stat *before, const struct stat *after) {
    int equal = before->st_dev == after->st_dev && before->st_ino == after->st_ino && (before->st_mode & S_IFMT) == (after->st_mode & S_IFMT) && before->st_size == after->st_size && before->st_mtime == after->st_mtime && before->st_ctime == after->st_ctime;
#ifdef __ANDROID__
    return equal && before->st_mtime_nsec == after->st_mtime_nsec && before->st_ctime_nsec == after->st_ctime_nsec;
#else
    return equal && before->st_mtim.tv_nsec == after->st_mtim.tv_nsec && before->st_ctim.tv_nsec == after->st_ctim.tv_nsec;
#endif
}

static int append_entry( stage_walk *walk, const narfs_entry *source, uint32_t ordinal, const unsigned char digest[32]) {
    narfs_stage_entry *grown = (narfs_stage_entry *) realloc( walk->result->entries, (walk->result->entry_count + 1U) * sizeof(*grown));
    narfs_stage_entry *entry;
    if (grown == NULL) {
        walk->error = NARFS_ERR_RESOURCE;
        return -1;
    }
    walk->result->entries = grown;
    entry = &grown[walk->result->entry_count];
    memset(entry, 0, sizeof(*entry));
    entry->relative_path = strdup(source->relative_path);
    if (entry->relative_path == NULL) {
        walk->error = NARFS_ERR_RESOURCE;
        return -1;
    }
    entry->type = source->type;
    entry->size = source->size;
    entry->blob_ordinal = ordinal;
    if (digest != NULL) memcpy(entry->sha256, digest, NARFS_SHA256_BYTES);
    walk->result->entry_count++;
    return 0;
}

static int stage_visitor( const narfs_entry *entry, int source_fd, void *context) {
    stage_walk *walk = (stage_walk *) context;
    uint32_t ordinal = UINT32_MAX;
    unsigned char digest[NARFS_SHA256_BYTES] = {0};
    if (entry->type == NARFS_ENTRY_DIRECTORY && entry->device == walk->result->token.root_device && entry->inode == walk->result->token.root_inode) {
        walk->error = NARFS_ERR_INVALID_OPTIONS;
        return -1;
    }
    if (entry->type == NARFS_ENTRY_DIRECTORY) return append_entry(walk, entry, ordinal, NULL);
    {
        char blob[16];
        struct stat before, after;
        unsigned char *buffer;
        uint64_t copied = 0;
        int output;
        narfs_sha256 hash;
        uint32_t index;
        ordinal = 0;
        for (index = 0; index < walk->result->entry_count; index++) if (walk->result->entries[index].blob_ordinal != UINT32_MAX) ordinal++;
        snprintf(blob, sizeof(blob), "b%06u", ordinal);
        do output = fstat(source_fd, &before);
        while (output != 0 && errno == EINTR);
        if (output != 0) {
            walk->error = system_error(errno);
            return -1;
        }
        do output = openat( walk->session_fd, blob, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        while (output < 0 && errno == EINTR);
        if (output < 0) {
            walk->error = system_error(errno);
            return -1;
        }
        buffer = (unsigned char *) malloc(walk->options->io_chunk);
        if (buffer == NULL) walk->error = NARFS_ERR_RESOURCE;
        narfs_sha256_init(&hash);
        while (walk->error == NARFS_OK && copied < entry->size) {
            size_t wanted = walk->options->io_chunk;
            ssize_t count;
            if ((uint64_t) wanted > entry->size - copied) wanted = (size_t) (entry->size - copied);
            do {
                count = injected( walk->options, NARFS_STAGE_TEST_READ, entry->relative_path);
                if (count == 0) count = read(source_fd, buffer, wanted);
            } while (count < 0 && errno == EINTR);
            if (count <= 0) {
                walk->error = count == 0 ? NARFS_ERR_TREE_CHANGED : system_error(errno);
                break;
            }
            narfs_sha256_update(&hash, buffer, (size_t) count);
            {
                size_t written = 0;
                while (written < (size_t) count) {
                    ssize_t part;
                    do {
                        part = injected( walk->options, NARFS_STAGE_TEST_WRITE, entry->relative_path);
                        if (part == 0) part = write( output, buffer + written, (size_t) count - written);
                    } while (part < 0 && errno == EINTR);
                    if (part <= 0) {
                        walk->error = system_error(errno);
                        break;
                    }
                    written += (size_t) part;
                }
            }
            copied += (uint64_t) count;
        }
        free(buffer);
        if (walk->error == NARFS_OK) {
            int synced;
            do {
                synced = injected( walk->options, NARFS_STAGE_TEST_SYNC, entry->relative_path);
                if (synced == 0) synced = fsync(output);
            } while (synced != 0 && errno == EINTR);
            if (synced != 0) walk->error = system_error(errno);
        }
        if (close_stage_fd( output, walk->options, entry->relative_path) != 0) {
            if (walk->error == NARFS_OK) walk->error = NARFS_ERR_CLOSE;
            else if (walk->cleanup_error == NARFS_OK) walk->cleanup_error = NARFS_ERR_CLOSE;
        }
        do output = fstat(source_fd, &after);
        while (output != 0 && errno == EINTR);
        if (output != 0 && walk->error == NARFS_OK) walk->error = system_error(errno);
        if (walk->error == NARFS_OK && !same_snapshot(&before, &after)) walk->error = NARFS_ERR_TREE_CHANGED;
        if (walk->error != NARFS_OK) {
            if (walk->error == NARFS_ERR_TREE_CHANGED && copied == entry->size) {
                narfs_sha256_final(&hash, digest);
                append_entry(walk, entry, ordinal, digest);
            }
            return -1;
        }
        narfs_sha256_final(&hash, digest);
    }
    return append_entry(walk, entry, ordinal, digest);
}

static int valid_session_name(const char *name) {
    unsigned index;
    if (name == NULL || name[0] != 's' || strlen(name) != 33) return 0;
    for (index = 1; index < 33; index++) if (!((name[index] >= '0' && name[index] <= '9') || (name[index] >= 'a' && name[index] <= 'f'))) return 0;
    return 1;
}

static int make_session( const char *staging_root, narfs_stage_token *token, int *root_fd, narfs_error *primary_error, narfs_error *cleanup_error, const narfs_stage_options *options) {
    unsigned char random[16];
    struct stat status;
    int entropy, session, attempt, result;
    *primary_error = NARFS_OK;
    do *root_fd = open( staging_root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    while (*root_fd < 0 && errno == EINTR);
    if (*root_fd < 0) return -1;
    do result = fstat(*root_fd, &status);
    while (result != 0 && errno == EINTR);
    if (result != 0 || !S_ISDIR(status.st_mode)) return -1;
    token->root_device = (uint64_t) status.st_dev;
    token->root_inode = (uint64_t) status.st_ino;
    do entropy = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    while (entropy < 0 && errno == EINTR);
    if (entropy < 0) return -1;
    for (attempt = 0; attempt < 16; attempt++) {
        size_t done = 0;
        while (done < sizeof(random)) {
            ssize_t count;
            do count = read(entropy, random + done, sizeof(random) - done);
            while (count < 0 && errno == EINTR);
            if (count <= 0) {
                close_stage_fd(entropy, options, "");
                return -1;
            }
            done += (size_t) count;
        }
        token->session_name[0] = 's';
        {
            static const char hex[] = "0123456789abcdef";
            unsigned index;
            for (index = 0; index < sizeof(random); index++) {
                token->session_name[1 + index * 2] = hex[random[index] >> 4];
                token->session_name[2 + index * 2] = hex[random[index] & 15];
            }
            token->session_name[33] = '\0';
        }
        do result = mkdirat(*root_fd, token->session_name, 0700);
        while (result != 0 && errno == EINTR);
        if (result == 0) break;
        if (errno != EEXIST) {
            close_stage_fd(entropy, options, "");
            return -1;
        }
    }
    if (attempt == 16) {
        if (close_stage_fd(entropy, options, "") != 0)
            *cleanup_error = NARFS_ERR_CLOSE;
        return -1;
    }
    do result = fstatat(
            *root_fd, token->session_name, &status, AT_SYMLINK_NOFOLLOW);
    while (result != 0 && errno == EINTR);
    if (result != 0 || !S_ISDIR(status.st_mode)) {
        int primary = errno;
        if (close_stage_fd(entropy, options, "") != 0)
            *cleanup_error = NARFS_ERR_CLOSE;
        if (unlink_stage(
                *root_fd, token->session_name, AT_REMOVEDIR, options, token->session_name) != 0 && *cleanup_error == NARFS_OK)
            *cleanup_error = system_error(errno);
        errno = primary;
        return -1;
    }
    token->stage_device = (uint64_t) status.st_dev;
    token->stage_inode = (uint64_t) status.st_ino;
    if (close_stage_fd(entropy, options, "") != 0) {
        *primary_error = NARFS_ERR_CLOSE;
        if (unlink_stage(
                *root_fd, token->session_name, AT_REMOVEDIR, options, token->session_name) != 0)
            *cleanup_error = system_error(errno);
        return -1;
    }
    do {
        session = injected( options, NARFS_STAGE_TEST_OPEN_SESSION, token->session_name);
        if (session == 0) session = openat(
                *root_fd, token->session_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    } while (session < 0 && errno == EINTR);
    if (session < 0) {
        int primary = errno;
        if (unlink_stage(
                *root_fd, token->session_name, AT_REMOVEDIR, options, token->session_name) != 0)
            *cleanup_error = system_error(errno);
        errno = primary;
        return -1;
    }
    do result = fstat(session, &status);
    while (result != 0 && errno == EINTR);
    if (result != 0 || (uint64_t) status.st_dev != token->stage_device || (uint64_t) status.st_ino != token->stage_inode) {
        int primary = result != 0 ? errno : EAGAIN;
        if (close_stage_fd(session, options, token->session_name) != 0)
            *cleanup_error = NARFS_ERR_CLOSE;
        if (unlink_stage(
                *root_fd, token->session_name, AT_REMOVEDIR, options, token->session_name) != 0 && *cleanup_error == NARFS_OK)
            *cleanup_error = system_error(errno);
        errno = primary;
        return -1;
    }
    return session;
}

narfs_stage_options narfs_default_stage_options(void) {
    narfs_stage_options options;
    memset(&options, 0, sizeof(options));
    options.inspect = narfs_default_options();
    options.io_chunk = NARFS_STAGE_DEFAULT_IO_CHUNK;
    return options;
}

narfs_error narfs_stage_discard( const char *staging_root, const narfs_stage_token *token, const narfs_stage_options *supplied) {
    narfs_stage_options defaults;
    const narfs_stage_options *options = supplied;
    struct stat status;
    DIR *directory;
    struct dirent *item;
    int root, session, copy, result;
    narfs_error error = NARFS_OK;
    if (options == NULL) {
        defaults = narfs_default_stage_options();
        options = &defaults;
    }
    if (staging_root == NULL || token == NULL || !valid_session_name(token->session_name)) return NARFS_ERR_INVALID_OPTIONS;
    do root = open( staging_root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    while (root < 0 && errno == EINTR);
    if (root < 0) return system_error(errno);
    do result = fstat(root, &status);
    while (result != 0 && errno == EINTR);
    if (result != 0) {
        error = system_error(errno);
        if (close_stage_fd(root, options, "") != 0 && error == NARFS_OK) error = NARFS_ERR_CLOSE;
        return error;
    }
    if (!S_ISDIR(status.st_mode) || (uint64_t) status.st_dev != token->root_device || (uint64_t) status.st_ino != token->root_inode) {
        close_stage_fd(root, options, "");
        return NARFS_ERR_TREE_CHANGED;
    }
    do result = fstatat( root, token->session_name, &status, AT_SYMLINK_NOFOLLOW);
    while (result != 0 && errno == EINTR);
    if (result != 0) {
        error = errno == ENOENT ? NARFS_OK : system_error(errno);
        if (close_stage_fd(root, options, "") != 0 && error == NARFS_OK) error = NARFS_ERR_CLOSE;
        return error;
    }
    if (!S_ISDIR(status.st_mode) || (uint64_t) status.st_dev != token->stage_device || (uint64_t) status.st_ino != token->stage_inode) {
        close_stage_fd(root, options, "");
        return NARFS_ERR_TREE_CHANGED;
    }
    do session = openat( root, token->session_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    while (session < 0 && errno == EINTR);
    if (session < 0) {
        error = changed_or_system_error(errno);
        close_stage_fd(root, options, "");
        return error;
    }
    do result = fstat(session, &status);
    while (result != 0 && errno == EINTR);
    if (result != 0 || (uint64_t) status.st_dev != token->stage_device || (uint64_t) status.st_ino != token->stage_inode) {
        error = result != 0 ? system_error(errno) : NARFS_ERR_TREE_CHANGED;
        close_stage_fd(session, options, token->session_name);
        close_stage_fd(root, options, "");
        return error;
    }
    do copy = dup(session);
    while (copy < 0 && errno == EINTR);
    directory = copy < 0 ? NULL : fdopendir(copy);
    if (directory == NULL) {
        if (copy >= 0) close(copy);
        close_stage_fd(session, options, token->session_name);
        close_stage_fd(root, options, "");
        return system_error(errno);
    }
    while (error == NARFS_OK) {
        size_t length;
        unsigned index;
        errno = 0;
        item = readdir(directory);
        if (item == NULL && errno == EINTR) continue;
        if (item == NULL) {
            if (errno != 0) error = system_error(errno);
            break;
        }
        if (strcmp(item->d_name, ".") == 0 || strcmp(item->d_name, "..") == 0) continue;
        length = strlen(item->d_name);
        if (length != 7 || item->d_name[0] != 'b') {
            error = NARFS_ERR_TREE_CHANGED;
            break;
        }
        for (index = 1; index < 7; index++) if (item->d_name[index] < '0' || item->d_name[index] > '9') error = NARFS_ERR_TREE_CHANGED;
        if (error != NARFS_OK) break;
        do result = fstatat( session, item->d_name, &status, AT_SYMLINK_NOFOLLOW);
        while (result != 0 && errno == EINTR);
        if (result != 0 || !S_ISREG(status.st_mode)) {
            error = result != 0 ? changed_or_system_error(errno) : NARFS_ERR_TREE_CHANGED;
            break;
        }
        if (unlink_stage( session, item->d_name, 0, options, item->d_name) != 0) {
            error = system_error(errno);
            break;
        }
    }
    if (closedir(directory) != 0 && error == NARFS_OK) error = NARFS_ERR_CLOSE;
    if (close_stage_fd(session, options, token->session_name) != 0 && error == NARFS_OK) error = NARFS_ERR_CLOSE;
    {
        narfs_error removal = NARFS_OK;
        do result = injected( options, NARFS_STAGE_TEST_UNLINK, token->session_name);
        while (result != 0 && errno == EINTR);
        if (result == 0) {
            do result = fstatat( root, token->session_name, &status, AT_SYMLINK_NOFOLLOW);
            while (result != 0 && errno == EINTR);
            if (result != 0) removal = changed_or_system_error(errno);
            else if (!S_ISDIR(status.st_mode) || (uint64_t) status.st_dev != token->stage_device || (uint64_t) status.st_ino != token->stage_inode) removal = NARFS_ERR_TREE_CHANGED;
            else {
                do result = unlinkat( root, token->session_name, AT_REMOVEDIR);
                while (result != 0 && errno == EINTR);
                if (result != 0) removal = system_error(errno);
            }
        } else removal = system_error(errno);
        if (error == NARFS_OK) error = removal;
    }
    if (close_stage_fd(root, options, "") != 0 && error == NARFS_OK) error = NARFS_ERR_CLOSE;
    return error;
}

narfs_stage_result narfs_stage_existing( const char *trusted_root, const char *target, const char *staging_root, const narfs_stage_options *supplied) {
    narfs_stage_options defaults;
    const narfs_stage_options *options = supplied;
    narfs_stage_result result;
    stage_walk walk;
    narfs_error creation_error = NARFS_OK, creation_cleanup = NARFS_OK;
    int root = -1, session = -1;
    memset(&result, 0, sizeof(result));
    result.inspected.state = NARFS_STATE_ERROR;
    if (options == NULL) {
        defaults = narfs_default_stage_options();
        options = &defaults;
    }
    if (options->io_chunk == 0 || staging_root == NULL) {
        result.inspected.error = NARFS_ERR_INVALID_OPTIONS;
        return result;
    }
    session = make_session( staging_root, &result.token, &root, &creation_error, &creation_cleanup, options);
    if (session < 0) {
        int primary = errno;
        result.inspected.error = creation_error != NARFS_OK ? creation_error : primary == EAGAIN ? NARFS_ERR_TREE_CHANGED : system_error(primary);
        result.inspected.cleanup_error = creation_cleanup;
        if (root >= 0 && close_stage_fd(root, options, "") != 0 && result.inspected.cleanup_error == NARFS_OK) result.inspected.cleanup_error = NARFS_ERR_CLOSE;
        return result;
    }
    memset(&walk, 0, sizeof(walk));
    walk.session_fd = session;
    walk.options = options;
    walk.result = &result;
    result.inspected = narfs_inspect( trusted_root, target, &options->inspect, stage_visitor, &walk);
    if ((result.inspected.storage_device == result.token.root_device && result.inspected.storage_inode == result.token.root_inode) || (result.inspected.target_device == result.token.root_device && result.inspected.target_inode == result.token.root_inode)) walk.error = NARFS_ERR_INVALID_OPTIONS;
    if (walk.error != NARFS_OK) {
        result.inspected.state = NARFS_STATE_ERROR;
        result.inspected.error = walk.error;
    }
    if (walk.cleanup_error != NARFS_OK && result.inspected.cleanup_error == NARFS_OK) result.inspected.cleanup_error = walk.cleanup_error;
    if (close_stage_fd(session, options, result.token.session_name) != 0) record_result_error(&result, NARFS_ERR_CLOSE);
    if (close_stage_fd(root, options, "") != 0) record_result_error(&result, NARFS_ERR_CLOSE);
    if (result.inspected.error != NARFS_OK || result.inspected.state == NARFS_STATE_ABSENT) {
        int absent = result.inspected.state == NARFS_STATE_ABSENT && result.inspected.error == NARFS_OK;
        narfs_error cleanup = narfs_stage_discard( staging_root, &result.token, options);
        if (absent && cleanup != NARFS_OK) record_result_error(&result, cleanup);
        else if (!absent && cleanup != NARFS_OK && result.inspected.cleanup_error == NARFS_OK) result.inspected.cleanup_error = cleanup;
        if (absent && cleanup == NARFS_OK) memset(&result.token, 0, sizeof(result.token));
    }
    return result;
}

void narfs_stage_result_dispose(narfs_stage_result *result) {
    uint32_t index;
    if (result == NULL) return;
    for (index = 0; index < result->entry_count; index++) free(result->entries[index].relative_path);
    free(result->entries);
    memset(result, 0, sizeof(*result));
}
