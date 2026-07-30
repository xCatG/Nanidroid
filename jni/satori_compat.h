// Must be force-included before the legacy Satori sources. Android's current
// libc++ exposes std::stoi and std::byte even in C++03 mode; Satori used those
// names for its own helpers two decades earlier. Include libc++ first, then
// remap only the legacy source tokens.
#include <cstddef>
#include <string>

#define byte satori_legacy_byte
#define stoi satori_legacy_stoi
