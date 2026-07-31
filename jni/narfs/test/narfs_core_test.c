#define _XOPEN_SOURCE 700
#define NARFS_TESTING 1

#include "../../jni/narfs/narfs_core.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define CHECK(value) do { \
    if (!(value)) { \
        fprintf(stderr, "check failed at %s:%d: %s\n", \
                __FILE__, __LINE__, #value); \
        exit(1); \
    } \
} while (0)

typedef struct capture {
    char paths[8][1025];
    int stored;
    int count;
} capture;

typedef struct fault {
    narfs_test_operation operation;
    int error;
    int remaining;
    const char *failure_path;
    const char *race_path;
    int raced;
} fault;

static int remove_item(
        const char *path,
        const struct stat *status,
        int type,
        struct FTW *walk) {
    (void) status;
    (void) type;
    (void) walk;
    return remove(path);
}

static void clear_directory(const char *path) {
    if (access(path, F_OK) == 0) {
        CHECK(nftw(path, remove_item, 32, FTW_DEPTH | FTW_PHYS) == 0);
    }
}

static void make_directory(const char *path) {
    CHECK(mkdir(path, 0700) == 0);
}

static void make_file(const char *path, off_t size) {
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY, 0600);
    CHECK(fd >= 0);
    CHECK(ftruncate(fd, size) == 0);
    CHECK(close(fd) == 0);
}

static void join_path(
        char *output,
        size_t capacity,
        const char *parent,
        const char *name) {
    size_t parent_length = strlen(parent);
    size_t name_length = strlen(name);
    CHECK(parent_length + 1 + name_length < capacity);
    memcpy(output, parent, parent_length);
    output[parent_length] = '/';
    memcpy(output + parent_length + 1, name, name_length + 1);
}

static int count_fds(void) {
    DIR *directory = opendir("/proc/self/fd");
    struct dirent *entry;
    int count = 0;
    CHECK(directory != NULL);
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0
                && strcmp(entry->d_name, "..") != 0) {
            count++;
        }
    }
    CHECK(closedir(directory) == 0);
    return count;
}

static int visit(
        const narfs_entry *entry, int fd, void *context) {
    capture *observed = (capture *) context;
    struct stat status;
    CHECK(fstat(fd, &status) == 0);
    CHECK((unsigned long long) status.st_dev == entry->device);
    CHECK((unsigned long long) status.st_ino == entry->inode);
    CHECK((entry->type == NARFS_ENTRY_FILE && S_ISREG(status.st_mode))
            || (entry->type == NARFS_ENTRY_DIRECTORY
                    && S_ISDIR(status.st_mode)));
    if (observed->stored < 8) {
        snprintf(
                observed->paths[observed->stored],
                sizeof(observed->paths[0]),
                "%s",
                entry->relative_path);
        observed->stored++;
    }
    observed->count++;
    return 0;
}

static int hook(
        narfs_test_operation operation,
        const char *relative_path,
        void *context) {
    fault *failure = (fault *) context;
    if (failure == NULL) {
        return 0;
    }
    if (failure->race_path != NULL
            && !failure->raced
            && operation == NARFS_TEST_OPEN
            && strcmp(relative_path, failure->race_path) == 0) {
        CHECK(rename("victim", "victim-old") == 0);
        make_file("victim", 0);
        failure->raced = 1;
        return 0;
    }
    if (failure->operation == operation
            && failure->remaining > 0
            && (failure->failure_path == NULL
                    || strcmp(relative_path,
                            failure->failure_path) == 0)) {
        failure->remaining--;
        return failure->error;
    }
    return 0;
}

static narfs_result inspect(
        const char *root,
        const char *target,
        capture *observed,
        fault *failure) {
    narfs_options options = narfs_default_options();
    options.test_hook = hook;
    options.test_context = failure;
    memset(observed, 0, sizeof(*observed));
    return narfs_inspect(root, target, &options, visit, observed);
}

static void test_absent_and_nested(const char *root) {
    char path[2048];
    capture observed;
    fault none = {0};
    narfs_result result = inspect(
            root, "missing", &observed, &none);
    CHECK(result.state == NARFS_STATE_ABSENT);
    CHECK(result.error == NARFS_OK);
    CHECK(observed.count == 0);
    CHECK(inspect(root, NULL, &observed, &none).error
            == NARFS_ERR_INVALID_TARGET);
    CHECK(inspect(root, "bad/name", &observed, &none).error
            == NARFS_ERR_INVALID_TARGET);

    snprintf(path, sizeof(path), "%s/ghost", root);
    make_directory(path);
    snprintf(path, sizeof(path), "%s/ghost/b", root);
    make_directory(path);
    snprintf(path, sizeof(path), "%s/ghost/a-empty", root);
    make_directory(path);
    snprintf(path, sizeof(path), "%s/ghost/b/z", root);
    make_file(path, 3);

    result = inspect(root, "ghost", &observed, &none);
    CHECK(result.state == NARFS_STATE_PRESENT);
    CHECK(result.error == NARFS_OK);
    CHECK(result.entry_count == 3);
    CHECK(result.total_file_size == 3);
    CHECK(strcmp(observed.paths[0], "a-empty") == 0);
    CHECK(strcmp(observed.paths[1], "b") == 0);
    CHECK(strcmp(observed.paths[2], "b/z") == 0);
}

static void test_links_and_specials(const char *root) {
    char target[2048];
    char child[2048];
    struct sockaddr_un address;
    capture observed;
    fault none = {0};
    int socket_fd;

    snprintf(target, sizeof(target), "%s/special", root);
    make_directory(target);
    join_path(child, sizeof(child), target, "link");
    CHECK(symlink("/tmp", child) == 0);
    CHECK(inspect(root, "special", &observed, &none).error
            == NARFS_ERR_SYMLINK);
    CHECK(unlink(child) == 0);
    CHECK(symlink("/definitely/missing", child) == 0);
    CHECK(inspect(root, "special", &observed, &none).error
            == NARFS_ERR_SYMLINK);
    CHECK(unlink(child) == 0);
    CHECK(mkfifo(child, 0600) == 0);
    CHECK(inspect(root, "special", &observed, &none).error
            == NARFS_ERR_SPECIAL_TYPE);
    CHECK(unlink(child) == 0);

    socket_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    CHECK(socket_fd >= 0);
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    CHECK(strlen(child) < sizeof(address.sun_path));
    memcpy(address.sun_path, child, strlen(child) + 1);
    CHECK(bind(socket_fd, (struct sockaddr *) &address,
            sizeof(address)) == 0);
    CHECK(inspect(root, "special", &observed, &none).error
            == NARFS_ERR_SPECIAL_TYPE);
    CHECK(close(socket_fd) == 0);
    CHECK(unlink(child) == 0);

    join_path(child, sizeof(child), target, "bad-\xff");
    make_file(child, 0);
    CHECK(inspect(root, "special", &observed, &none).error
            == NARFS_ERR_INVALID_NAME);
    CHECK(unlink(child) == 0);
    join_path(child, sizeof(child), target, "overlong-\xe0\x82\x80");
    make_file(child, 0);
    CHECK(inspect(root, "special", &observed, &none).error
            == NARFS_ERR_INVALID_NAME);
}

static void make_chain(
        const char *root,
        const char *target,
        int depth,
        int component_length) {
    char path[4096];
    int index;
    snprintf(path, sizeof(path), "%s/%s", root, target);
    make_directory(path);
    for (index = 0; index < depth; index++) {
        size_t used = strlen(path);
        int fill = component_length;
        CHECK(used + 1 + (size_t) fill < sizeof(path));
        path[used++] = '/';
        memset(path + used, 'a' + index % 26, (size_t) fill);
        path[used + (size_t) fill] = '\0';
        make_directory(path);
    }
}

static void test_limits(const char *root) {
    char target[2048];
    char path[4096];
    char name[300];
    capture observed;
    fault none = {0};
    narfs_result result;
    int index;

    snprintf(target, sizeof(target), "%s/count", root);
    make_directory(target);
    for (index = 0; index < 10000; index++) {
        snprintf(path, sizeof(path), "%s/f%05d", target, index);
        make_file(path, 0);
    }
    result = inspect(root, "count", &observed, &none);
    CHECK(result.error == NARFS_OK);
    CHECK(result.entry_count == 10000);
    snprintf(path, sizeof(path), "%s/overflow", target);
    make_file(path, 0);
    CHECK(inspect(root, "count", &observed, &none).error
            == NARFS_ERR_ENTRY_COUNT_LIMIT);
    clear_directory(target);

    memset(name, 'x', 255);
    name[255] = '\0';
    snprintf(path, sizeof(path), "%s/%s", root, name);
    make_directory(path);
    CHECK(inspect(root, name, &observed, &none).error == NARFS_OK);
    clear_directory(path);
    memset(name, 'x', 256);
    name[256] = '\0';
    CHECK(inspect(root, name, &observed, &none).error
            == NARFS_ERR_COMPONENT_LIMIT);

    make_chain(root, "depth32", 32, 1);
    CHECK(inspect(root, "depth32", &observed, &none).error
            == NARFS_OK);
    make_chain(root, "depth33", 33, 1);
    CHECK(inspect(root, "depth33", &observed, &none).error
            == NARFS_ERR_DEPTH_LIMIT);

    make_chain(root, "path1024", 5, 204);
    CHECK(inspect(root, "path1024", &observed, &none).error
            == NARFS_OK);
    make_chain(root, "path1025", 4, 204);
    snprintf(path, sizeof(path), "%s/path1025", root);
    for (index = 0; index < 4; index++) {
        size_t used = strlen(path);
        path[used++] = '/';
        memset(path + used, 'a' + index % 26, 204);
        path[used + 204] = '\0';
    }
    {
        size_t used = strlen(path);
        path[used++] = '/';
        memset(path + used, 'z', 205);
        path[used + 205] = '\0';
        make_directory(path);
    }
    CHECK(inspect(root, "path1025", &observed, &none).error
            == NARFS_ERR_PATH_LIMIT);

    snprintf(target, sizeof(target), "%s/sizes", root);
    make_directory(target);
    for (index = 0; index < 4; index++) {
        snprintf(path, sizeof(path), "%s/f%d", target, index);
        make_file(path, 128LL * 1024 * 1024);
    }
    CHECK(inspect(root, "sizes", &observed, &none).error
            == NARFS_OK);
    snprintf(path, sizeof(path), "%s/extra", target);
    make_file(path, 1);
    CHECK(inspect(root, "sizes", &observed, &none).error
            == NARFS_ERR_TOTAL_SIZE_LIMIT);
    clear_directory(target);
    make_directory(target);
    snprintf(path, sizeof(path), "%s/large", target);
    make_file(path, 128LL * 1024 * 1024 + 1);
    CHECK(inspect(root, "sizes", &observed, &none).error
            == NARFS_ERR_FILE_SIZE_LIMIT);
}

static void test_faults_race_and_fds(const char *root) {
    char target[2048];
    char victim[2048];
    capture observed;
    fault injected;
    narfs_result result;
    int before = count_fds();
    int operation;

    snprintf(target, sizeof(target), "%s/faults", root);
    make_directory(target);
    join_path(victim, sizeof(victim), target, "victim");
    make_file(victim, 0);

    for (operation = NARFS_TEST_OPEN;
            operation <= NARFS_TEST_READDIR;
            operation++) {
        memset(&injected, 0, sizeof(injected));
        injected.operation = (narfs_test_operation) operation;
        injected.error = EINTR;
        injected.remaining = 1;
        result = inspect(root, "faults", &observed, &injected);
        CHECK(result.error == NARFS_OK);
    }

    CHECK(chdir(target) == 0);
    memset(&injected, 0, sizeof(injected));
    injected.race_path = "victim";
    result = inspect(root, "faults", &observed, &injected);
    CHECK(result.error == NARFS_ERR_TREE_CHANGED);
    CHECK(chdir("/") == 0);

    memset(&injected, 0, sizeof(injected));
    injected.operation = NARFS_TEST_OPEN;
    injected.error = EACCES;
    injected.remaining = 1;
    CHECK(inspect(root, "faults", &observed, &injected).error
            == NARFS_ERR_PERMISSION);

    memset(&injected, 0, sizeof(injected));
    injected.operation = NARFS_TEST_FSTAT;
    injected.error = EACCES;
    injected.remaining = 1;
    injected.failure_path = "victim";
    CHECK(inspect(root, "faults", &observed, &injected).error
            == NARFS_ERR_PERMISSION);

    memset(&injected, 0, sizeof(injected));
    injected.operation = NARFS_TEST_READDIR;
    injected.error = EIO;
    injected.remaining = 1;
    CHECK(inspect(root, "faults", &observed, &injected).error
            == NARFS_ERR_IO);

    memset(&injected, 0, sizeof(injected));
    injected.operation = NARFS_TEST_CLOSE;
    injected.error = EIO;
    injected.remaining = 1;
    result = inspect(root, "faults", &observed, &injected);
    CHECK(result.error == NARFS_ERR_CLOSE
            || result.cleanup_error == NARFS_ERR_CLOSE);
    CHECK(count_fds() == before);
}

int main(void) {
    char root[] = "/tmp/narfs-core-test-XXXXXX";
    CHECK(mkdtemp(root) != NULL);
    test_absent_and_nested(root);
    clear_directory(root);
    make_directory(root);
    test_links_and_specials(root);
    clear_directory(root);
    make_directory(root);
    test_limits(root);
    clear_directory(root);
    make_directory(root);
    test_faults_race_and_fds(root);
    clear_directory(root);
    puts("narfs_core host tests passed");
    return 0;
}
