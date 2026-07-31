#include "narfs_core.h"

static int visit(const narfs_entry *entry, int fd, void *context) {
    (void) entry;
    (void) fd;
    (void) context;
    return 0;
}

int main(int argc, char **argv) {
    narfs_options options = narfs_default_options();
    if (argc != 3) {
        return 2;
    }
    return narfs_inspect(argv[1], argv[2], &options, visit, 0).error;
}
