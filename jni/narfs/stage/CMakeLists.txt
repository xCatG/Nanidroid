add_library(narfs_stage STATIC ../narfs_stage.c)
target_include_directories(narfs_stage PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_compile_options(narfs_stage PRIVATE -std=c99 -Wall -Wextra -Werror)

add_executable(
  narfs_stage_link_probe
  ${CMAKE_CURRENT_SOURCE_DIR}/../test/narfs_stage_link_probe.c)
target_compile_options(
  narfs_stage_link_probe PRIVATE -std=c99 -Wall -Wextra -Werror)
target_link_libraries(
  narfs_stage_link_probe narfs_stage narfs_core narfs_sha256)
set_target_properties(
  narfs_stage_link_probe
  PROPERTIES LINKER_LANGUAGE C LINK_FLAGS "-Wl,--no-undefined")
