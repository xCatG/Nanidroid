cmake_minimum_required(VERSION 3.22.1)
project(nanidroid_native LANGUAGES C)

# The modern application has one current native component.  Historical SHIORI
# engines live only on the archived reference branch.
add_subdirectory(narfs)
