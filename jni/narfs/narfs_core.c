#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "narfs_core.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif

typedef struct name_list {
    char **items;
    size_t count;
    size_t next;
} name_list;

typedef struct frame {
    int fd;
    uint32_t depth;
    uint64_t device;
    uint64_t inode;
    struct stat snapshot;
    char path[NARFS_MAX_PATH_BYTES + 1];
    name_list names;
    int loaded;
} frame;

typedef struct walk {
    narfs_options options;
    narfs_visitor visitor;
    void *visitor_context;
    narfs_result result;
    uint32_t names_seen;
} walk;

static void record_error(walk *state, narfs_error error) {
    if (state->result.error == NARFS_OK) {
        state->result.error = error;
        state->result.state = NARFS_STATE_ERROR;
    } else if (state->result.cleanup_error == NARFS_OK) {
        state->result.cleanup_error = error;
    }
}

static int injected(
        walk *state,
        narfs_test_operation operation,
        const char *path) {
    int error = state->options.test_hook == NULL
            ? 0
            : state->options.test_hook(
                    operation,
                    path == NULL ? "" : path,
                    state->options.test_context);
    if (error != 0) {
        errno = error;
        return -1;
    }
    return 0;
}

static narfs_error system_error(int error) {
    if (error == EACCES || error == EPERM) {
        return NARFS_ERR_PERMISSION;
    }
    if (error == EMFILE || error == ENFILE
            || error == ENOMEM) {
        return NARFS_ERR_RESOURCE;
    }
    return NARFS_ERR_IO;
}

static narfs_error changed_or_system_error(int error) {
    return error == ENOENT || error == ENOTDIR || error == ELOOP
            ? NARFS_ERR_TREE_CHANGED : system_error(error);
}

static int open_root(
        walk *state, const char *path, int flags) {
    int fd;
    do {
        if (injected(state, NARFS_TEST_OPEN, path) != 0) {
            fd = -1;
        } else {
            fd = open(path, flags);
        }
    } while (fd < 0 && errno == EINTR);
    return fd;
}

static int open_child(
        walk *state,
        int parent,
        const char *name,
        int flags,
        const char *path) {
    int fd;
    do {
        if (injected(state, NARFS_TEST_OPEN, path) != 0) {
            fd = -1;
        } else {
            fd = openat(parent, name, flags);
        }
    } while (fd < 0 && errno == EINTR);
    return fd;
}

static int stat_fd(
        walk *state,
        int fd,
        struct stat *status,
        const char *path) {
    int result;
    do {
        if (injected(state, NARFS_TEST_FSTAT, path) != 0) {
            result = -1;
        } else {
            result = fstat(fd, status);
        }
    } while (result != 0 && errno == EINTR);
    return result;
}

static int stat_child(
        walk *state,
        int parent,
        const char *name,
        struct stat *status,
        const char *path) {
    int result;
    do {
        if (injected(state, NARFS_TEST_FSTATAT, path) != 0) {
            result = -1;
        } else {
            result = fstatat(
                    parent,
                    name,
                    status,
                    AT_SYMLINK_NOFOLLOW);
        }
    } while (result != 0 && errno == EINTR);
    return result;
}

static int close_fd(
        walk *state, int fd, const char *path) {
    int result = close(fd);
    if (result == 0
            && injected(state, NARFS_TEST_CLOSE, path) != 0) {
        return -1;
    }
    return result;
}

static int close_directory(
        walk *state, DIR *directory, const char *path) {
    int result = closedir(directory);
    if (result == 0
            && injected(state, NARFS_TEST_CLOSE, path) != 0) {
        return -1;
    }
    return result;
}

static int valid_utf8_name(
        const char *name, uint32_t component_limit) {
    const unsigned char *cursor;
    size_t length;
    if (name == NULL) {
        return 0;
    }
    cursor = (const unsigned char *) name;
    length = strlen(name);
    if (length == 0 || length > component_limit
            || strcmp(name, ".") == 0
            || strcmp(name, "..") == 0) {
        return 0;
    }
    while (*cursor != 0) {
        uint32_t value;
        uint32_t minimum = 0;
        int continuation;
        if (*cursor < 0x80) {
            value = *cursor++;
            continuation = 0;
        } else if ((*cursor & 0xe0) == 0xc0) {
            value = *cursor++ & 0x1f;
            continuation = 1;
            minimum = 0x80;
            if (value < 2) return 0;
        } else if ((*cursor & 0xf0) == 0xe0) {
            value = *cursor++ & 0x0f;
            continuation = 2;
            minimum = 0x800;
        } else if ((*cursor & 0xf8) == 0xf0) {
            value = *cursor++ & 0x07;
            continuation = 3;
            minimum = 0x10000;
        } else {
            return 0;
        }
        while (continuation-- > 0) {
            if ((*cursor & 0xc0) != 0x80) return 0;
            value = (value << 6) | (*cursor++ & 0x3f);
        }
        if (value < minimum || value > 0x10ffff
                || (value >= 0xd800 && value <= 0xdfff)
                || value < 0x20
                || (value >= 0x7f && value <= 0x9f)
                || value == '/' || value == '\\'
                || value == ':') {
            return 0;
        }
    }
    return 1;
}

static int compare_names(
        const void *left, const void *right) {
    return strcmp(
            *(const char * const *) left,
            *(const char * const *) right);
}

static void free_names(name_list *names) {
    size_t index;
    for (index = 0; index < names->count; index++) {
        free(names->items[index]);
    }
    free(names->items);
    memset(names, 0, sizeof(*names));
}

static int load_names(walk *state, frame *current) {
    int copy;
    DIR *directory;
    struct dirent *entry;
    do {
        copy = dup(current->fd);
    } while (copy < 0 && errno == EINTR);
    if (copy < 0) {
        record_error(state, system_error(errno));
        return -1;
    }
    directory = fdopendir(copy);
    if (directory == NULL) {
        int saved_error = errno;
        record_error(state, system_error(saved_error));
        if (close_fd(state, copy, current->path) != 0) {
            record_error(state, NARFS_ERR_CLOSE);
        }
        return -1;
    }
    while (1) {
        errno = 0;
        if (injected(
                state,
                NARFS_TEST_READDIR,
                current->path) != 0) {
            entry = NULL;
        } else {
            entry = readdir(directory);
        }
        if (entry == NULL && errno == EINTR) {
            continue;
        }
        if (entry == NULL) {
            if (errno != 0) {
                record_error(state, system_error(errno));
            }
            break;
        }
        if (strcmp(entry->d_name, ".") == 0
                || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        if (!valid_utf8_name(
                entry->d_name,
                state->options.max_component_bytes)) {
            record_error(
                    state,
                    strlen(entry->d_name)
                            > state->options.max_component_bytes
                            ? NARFS_ERR_COMPONENT_LIMIT
                            : NARFS_ERR_INVALID_NAME);
            break;
        }
        state->names_seen++;
        if (state->names_seen > state->options.max_entries) {
            record_error(
                    state, NARFS_ERR_ENTRY_COUNT_LIMIT);
            break;
        }
        {
            char **grown = (char **) realloc(
                    current->names.items,
                    (current->names.count + 1)
                            * sizeof(char *));
            if (grown == NULL) {
                record_error(state, NARFS_ERR_RESOURCE);
                break;
            }
            current->names.items = grown;
            current->names.items[current->names.count] =
                    strdup(entry->d_name);
            if (current->names.items[current->names.count]
                    == NULL) {
                record_error(state, NARFS_ERR_RESOURCE);
                break;
            }
            current->names.count++;
        }
    }
    if (close_directory(
            state, directory, current->path) != 0) {
        record_error(state, NARFS_ERR_CLOSE);
    }
    if (state->result.error != NARFS_OK) {
        return -1;
    }
    if (current->names.count > 1) {
        qsort(
                current->names.items,
                current->names.count,
                sizeof(char *),
                compare_names);
    }
    current->loaded = 1;
    return 0;
}

static int same_node(
        const struct stat *left,
        const struct stat *right) {
    return left->st_dev == right->st_dev
            && left->st_ino == right->st_ino
            && (left->st_mode & S_IFMT)
                    == (right->st_mode & S_IFMT);
}

static int same_snapshot(
        const struct stat *left, const struct stat *right) {
    int equal = same_node(left, right)
            && left->st_size == right->st_size
            && left->st_mtime == right->st_mtime
            && left->st_ctime == right->st_ctime;
#ifdef __ANDROID__
    return equal && left->st_mtime_nsec == right->st_mtime_nsec
            && left->st_ctime_nsec == right->st_ctime_nsec;
#else
    return equal && left->st_mtim.tv_nsec == right->st_mtim.tv_nsec
            && left->st_ctim.tv_nsec == right->st_ctim.tv_nsec;
#endif
}

static int valid_options(const narfs_options *options) {
    return options != NULL
            && options->max_entries > 0
            && options->max_entries <= NARFS_MAX_ENTRIES
            && options->max_depth > 0
            && options->max_depth <= NARFS_MAX_DEPTH
            && options->max_component_bytes > 0
            && options->max_component_bytes
                    <= NARFS_MAX_COMPONENT_BYTES
            && options->max_path_bytes > 0
            && options->max_path_bytes <= NARFS_MAX_PATH_BYTES
            && options->max_file_bytes > 0
            && options->max_file_bytes <= NARFS_MAX_FILE_BYTES
            && options->max_total_bytes > 0
            && options->max_total_bytes <= NARFS_MAX_TOTAL_BYTES;
}

narfs_options narfs_default_options(void) {
    narfs_options options;
    memset(&options, 0, sizeof(options));
    options.max_entries = NARFS_MAX_ENTRIES;
    options.max_depth = NARFS_MAX_DEPTH;
    options.max_component_bytes = NARFS_MAX_COMPONENT_BYTES;
    options.max_path_bytes = NARFS_MAX_PATH_BYTES;
    options.max_file_bytes = NARFS_MAX_FILE_BYTES;
    options.max_total_bytes = NARFS_MAX_TOTAL_BYTES;
    return options;
}

narfs_result narfs_inspect(
        const char *trusted_root,
        const char *target,
        const narfs_options *options,
        narfs_visitor visitor,
        void *visitor_context) {
    walk state;
    frame stack[NARFS_MAX_DEPTH + 1];
    size_t stack_size = 0;
    int root = -1;
    int target_fd = -1;
    struct stat before;
    struct stat opened;
    struct stat after;
    memset(&state, 0, sizeof(state));
    memset(stack, 0, sizeof(stack));
    state.result.state = NARFS_STATE_ERROR;
    state.options = options == NULL
            ? narfs_default_options() : *options;
    state.visitor = visitor;
    state.visitor_context = visitor_context;
    if (trusted_root == NULL || visitor == NULL
            || !valid_options(&state.options)) {
        record_error(&state, NARFS_ERR_INVALID_OPTIONS);
        return state.result;
    }
    if (!valid_utf8_name(
            target, state.options.max_component_bytes)) {
        record_error(
                &state,
                target != NULL
                        && strlen(target)
                                > state.options.max_component_bytes
                        ? NARFS_ERR_COMPONENT_LIMIT
                        : NARFS_ERR_INVALID_TARGET);
        return state.result;
    }
    root = open_root(
            &state,
            trusted_root,
            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (root < 0) {
        record_error(
                &state,
                errno == ELOOP
                        ? NARFS_ERR_SYMLINK
                        : errno == ENOTDIR
                                ? NARFS_ERR_ROOT_TYPE
                                : system_error(errno));
        return state.result;
    }
    if (stat_fd(&state, root, &opened, "") != 0) {
        record_error(&state, system_error(errno));
        goto cleanup;
    }
    if (!S_ISDIR(opened.st_mode)) {
        record_error(&state, NARFS_ERR_ROOT_TYPE);
        goto cleanup;
    }
    state.result.storage_device = (uint64_t) opened.st_dev;
    state.result.storage_inode = (uint64_t) opened.st_ino;
    if (stat_child(
            &state, root, target, &before, "") != 0) {
        if (errno == ENOENT) {
            state.result.state = NARFS_STATE_ABSENT;
            goto cleanup;
        }
        record_error(&state, system_error(errno));
        goto cleanup;
    }
    if (S_ISLNK(before.st_mode)) {
        record_error(&state, NARFS_ERR_SYMLINK);
        goto cleanup;
    }
    if (!S_ISDIR(before.st_mode)) {
        record_error(&state, NARFS_ERR_TARGET_TYPE);
        goto cleanup;
    }
    target_fd = open_child(
            &state,
            root,
            target,
            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC,
            "");
    if (target_fd < 0) {
        record_error(&state, changed_or_system_error(errno));
        goto cleanup;
    }
    if (stat_fd(&state, target_fd, &opened, "") != 0) {
        record_error(&state, system_error(errno));
        goto cleanup;
    }
    if (stat_child(&state, root, target, &after, "") != 0) {
        record_error(&state,
                errno == ENOENT
                        ? NARFS_ERR_TREE_CHANGED
                        : system_error(errno));
        goto cleanup;
    }
    if (!same_node(&before, &opened)
            || !same_node(&opened, &after)) {
        record_error(&state, NARFS_ERR_TREE_CHANGED);
        goto cleanup;
    }
    state.result.target_device = (uint64_t) opened.st_dev;
    state.result.target_inode = (uint64_t) opened.st_ino;
    if (close_fd(&state, root, "") != 0) {
        root = -1;
        record_error(&state, NARFS_ERR_CLOSE);
        goto cleanup;
    }
    root = -1;
    stack[0].fd = target_fd;
    stack[0].device = (uint64_t) opened.st_dev;
    stack[0].inode = (uint64_t) opened.st_ino;
    stack[0].snapshot = opened;
    target_fd = -1;
    stack_size = 1;
    state.result.state = NARFS_STATE_PRESENT;

    while (stack_size > 0 && state.result.error == NARFS_OK) {
        frame *current = &stack[stack_size - 1];
        char relative[NARFS_MAX_PATH_BYTES + 1];
        const char *name;
        uint32_t depth;
        int child;
        int directory;
        narfs_entry entry;
        size_t index;
        size_t name_length;
        size_t parent_length;
        if (!current->loaded && load_names(&state, current) != 0) {
            break;
        }
        if (current->names.next == current->names.count) {
            if (stat_fd(&state, current->fd, &after, current->path) != 0) {
                record_error(&state, system_error(errno));
            } else if (!same_snapshot(&current->snapshot, &after)) {
                record_error(&state, NARFS_ERR_TREE_CHANGED);
            }
            free_names(&current->names);
            if (close_fd(
                    &state, current->fd, current->path) != 0) {
                record_error(&state, NARFS_ERR_CLOSE);
            }
            memset(current, 0, sizeof(*current));
            stack_size--;
            continue;
        }
        name = current->names.items[current->names.next++];
        name_length = strlen(name);
        parent_length = strlen(current->path);
        depth = current->depth + 1;
        if (depth > state.options.max_depth) {
            record_error(&state, NARFS_ERR_DEPTH_LIMIT);
            break;
        }
        if (current->path[0] == '\0') {
            if (name_length > state.options.max_path_bytes) {
                record_error(&state, NARFS_ERR_PATH_LIMIT);
                break;
            }
            memcpy(relative, name, name_length + 1);
        } else {
            if (parent_length + 1 + name_length
                    > state.options.max_path_bytes) {
                record_error(&state, NARFS_ERR_PATH_LIMIT);
                break;
            }
            memcpy(relative, current->path, parent_length);
            relative[parent_length] = '/';
            memcpy(relative + parent_length + 1, name, name_length + 1);
        }
        if (stat_child(
                &state,
                current->fd,
                name,
                &before,
                relative) != 0) {
            record_error(
                    &state,
                    errno == ENOENT
                            ? NARFS_ERR_TREE_CHANGED
                            : system_error(errno));
            break;
        }
        if (S_ISLNK(before.st_mode)) {
            record_error(&state, NARFS_ERR_SYMLINK);
            break;
        }
        directory = S_ISDIR(before.st_mode);
        if (!directory && !S_ISREG(before.st_mode)) {
            record_error(&state, NARFS_ERR_SPECIAL_TYPE);
            break;
        }
        child = open_child(
                &state,
                current->fd,
                name,
                O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK
                        | (directory ? O_DIRECTORY : 0),
                relative);
        if (child < 0) {
            record_error(&state, changed_or_system_error(errno));
            break;
        }
        if (stat_fd(&state, child, &opened, relative) != 0) {
            record_error(&state, system_error(errno));
            if (close_fd(&state, child, relative) != 0) {
                record_error(&state, NARFS_ERR_CLOSE);
            }
            break;
        }
        if (stat_child(
                &state, current->fd, name, &after, relative) != 0) {
            record_error(&state,
                    errno == ENOENT
                            ? NARFS_ERR_TREE_CHANGED
                            : system_error(errno));
        } else if (!same_node(&before, &opened)
                || !same_node(&opened, &after)) {
            record_error(&state, NARFS_ERR_TREE_CHANGED);
        }
        if (state.result.error != NARFS_OK) {
            if (close_fd(&state, child, relative) != 0) {
                record_error(&state, NARFS_ERR_CLOSE);
            }
            break;
        }
        if (!directory) {
            if (opened.st_size < 0
                    || (uint64_t) opened.st_size
                            > state.options.max_file_bytes) {
                record_error(
                        &state, NARFS_ERR_FILE_SIZE_LIMIT);
            } else if ((uint64_t) opened.st_size
                    > state.options.max_total_bytes
                            - state.result.total_file_size) {
                record_error(
                        &state, NARFS_ERR_TOTAL_SIZE_LIMIT);
            } else {
                state.result.total_file_size +=
                        (uint64_t) opened.st_size;
            }
        } else {
            for (index = 0; index < stack_size; index++) {
                if (stack[index].device
                                == (uint64_t) opened.st_dev
                        && stack[index].inode
                                == (uint64_t) opened.st_ino) {
                    record_error(&state, NARFS_ERR_CYCLE);
                    break;
                }
            }
        }
        if (state.result.error != NARFS_OK) {
            if (close_fd(&state, child, relative) != 0) {
                record_error(&state, NARFS_ERR_CLOSE);
            }
            break;
        }
        memset(&entry, 0, sizeof(entry));
        entry.relative_path = relative;
        entry.type = directory
                ? NARFS_ENTRY_DIRECTORY : NARFS_ENTRY_FILE;
        entry.size = directory ? 0 : (uint64_t) opened.st_size;
        entry.device = (uint64_t) opened.st_dev;
        entry.inode = (uint64_t) opened.st_ino;
        state.result.entry_count++;
        if (state.visitor(
                &entry, child, state.visitor_context) != 0) {
            record_error(&state, NARFS_ERR_VISITOR);
        }
        if (state.result.error == NARFS_OK) {
            if (stat_fd(&state, child, &after, relative) != 0) {
                record_error(&state, system_error(errno));
            } else if (!same_snapshot(&opened, &after)) {
                record_error(&state, NARFS_ERR_TREE_CHANGED);
            } else if (stat_child(
                    &state, current->fd, name, &after, relative) != 0) {
                record_error(&state, changed_or_system_error(errno));
            } else if (!same_snapshot(&opened, &after)) {
                record_error(&state, NARFS_ERR_TREE_CHANGED);
            }
        }
        if (directory && state.result.error == NARFS_OK) {
            frame *next = &stack[stack_size++];
            next->fd = child;
            next->depth = depth;
            next->device = entry.device;
            next->inode = entry.inode;
            next->snapshot = opened;
            memcpy(next->path, relative, strlen(relative) + 1);
        } else if (close_fd(&state, child, relative) != 0) {
            record_error(&state, NARFS_ERR_CLOSE);
        }
    }

cleanup:
    while (stack_size > 0) {
        frame *current = &stack[--stack_size];
        free_names(&current->names);
        if (close_fd(
                &state, current->fd, current->path) != 0) {
            record_error(&state, NARFS_ERR_CLOSE);
        }
    }
    if (target_fd >= 0
            && close_fd(&state, target_fd, "") != 0) {
        record_error(&state, NARFS_ERR_CLOSE);
    }
    if (root >= 0 && close_fd(&state, root, "") != 0) {
        record_error(&state, NARFS_ERR_CLOSE);
    }
    return state.result;
}
