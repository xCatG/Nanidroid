add_library(narfs_sha256 STATIC ../narfs_sha256.c)
target_include_directories(narfs_sha256 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_compile_options(narfs_sha256 PRIVATE -std=c99 -Wall -Wextra -Werror)

add_executable(
  narfs_sha256_link_probe
  ${CMAKE_CURRENT_SOURCE_DIR}/../test/narfs_sha256_link_probe.c)
target_compile_options(
  narfs_sha256_link_probe PRIVATE -std=c99 -Wall -Wextra -Werror)
target_link_libraries(narfs_sha256_link_probe narfs_sha256)
set_target_properties(
  narfs_sha256_link_probe
  PROPERTIES LINKER_LANGUAGE C LINK_FLAGS "-Wl,--no-undefined")
