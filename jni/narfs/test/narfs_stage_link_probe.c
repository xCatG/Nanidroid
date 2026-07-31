#include "narfs_stage.h"

int main(void) {
    narfs_stage_options options = narfs_default_stage_options();
    narfs_stage_clone_mapping mapping = {0};
    narfs_stage_result result = narfs_stage_existing(
        "/data/local/tmp", "missing", "/data/local/tmp", &options);
    narfs_stage_clone_result clone = narfs_stage_clone_retained(
        "/data/local/tmp", &result.token, &mapping, 1, &options);
    narfs_stage_result_dispose(&result);
    if (clone.error != NARFS_ERR_INVALID_OPTIONS) return 1;
    return narfs_stage_discard("/data/local/tmp", &result.token, &options)
        == NARFS_ERR_INVALID_OPTIONS ? 0 : 1;
}
