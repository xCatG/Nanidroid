# Default ghost dialogue and host commands

Implemented in [PR #442](https://github.com/xCatG/Nanidroid/pull/442).
The author approved the archive-owned design on 2026-10-12. This record replaces
this PR's separate plans and review reports; their history remains in Git.

## Behavior and ownership

The original PNG artwork is retained. Tapping 郭嘉榆 opens introduction and usage
help; tapping Droid opens Ghosts, About and debug tools. Dialogue, touch mapping,
choices and lifecycle scripts live in `nanidroid.zip` under
`ghost/master/{en,ja,zh-TW}/content.txt`. Installed dictionaries are authoritative,
including user-customized content. No APK assistant content service or UI-specific
SHIORI wrapper remains.

`BuiltInShiori` uses ordinary event tables with generic selectors:
`Event@r3=N`, `Event@debug`, and `Event@r3=N@debug`. Lookup priority is
reference+debug, reference, debug, then plain event. Reference selectors require
canonical nonnegative Int values; mouse selectors also validate References0–2.
Recognized selector rows cannot be invoked as direct event IDs. Unrecognized keys
remain ordinary table keys. Kotlin does not assign character roles or help topics.

English uses the English dictionary; zh-TW, zh-Hant, zh-HK and zh-MO use Traditional
Chinese. Japanese and unsupported locales, including explicit zh-Hans, use the
Japanese fallback. Reads are bounded to 64 KiB. Dialogue locale is selected at
process startup; changing language requires a restart. Debug-dialog labels use
Android English/Japanese/Traditional Chinese resources independently.

## Script delivery

| SakuraScript | Host behavior | Permission |
| --- | --- | --- |
| `\![open,ghostexplorer]` | Ghosts/import dialog | Any active ghost |
| `\![open,configurationdialog,about]` | About/licenses dialog | Any active ghost |
| `\![open,developer]` | Debug tools | Verified bundled BuiltInShiori session and debuggable app |

`about` is a Nanidroid-specific target. Unknown or malformed targets are ignored.
Commands execute when playback reaches them, respecting waits and input pauses.
ScriptPlayer emits transient commands; GhostRuntime assigns occurrence IDs and
holds one session-owned FIFO. Compose claims a request once, when no conflicting
modal is visible. Repeated authored commands remain separate occurrences.

Replacement, pause, switch and close invalidate pending delivery. Natural queue
advancement preserves reached commands. Request-time epochs reject stale replies
across pause/resume. Denied commands retain valid surrounding dialogue; command-only
scripts still publish requests. Dialogs are session-scoped and do not replay after
recreation. The archive owns first boot and close scripts; ordinary table engines
retain their existing close fallback.

Substituted foreign names remain literal data. Backslashes, percent signs, commas
and brackets are serialized; playback decodes without rescanning emitted text.
Argument consumers identify boundaries before decoding, preserving choice labels,
SHIORI references and input IDs. Host-command matching retains the exact allowlist.

## Existing-install refresh

Refresh only the three managed dictionaries after verifying the archive pin,
bundled install/descriptor identity, and confined nonlinked paths. Known previous
shipped bytes are replaced; current bytes are unchanged; missing files are added;
other existing bytes are preserved as customized. Each replacement uses an
exclusively created adjacent temporary file, an ownership recheck and atomic move.
Interrupted refresh can retry by hash; there is no directory replacement or sweep.

PNGs, descriptors, shell files, saved state, activation markers, unknown files and
imported ghosts remain intact. Customized dictionaries may intentionally lack the
new menus. Fresh-install/no-legacy-migration behavior is unchanged.

See the [acceptance record](../../testing/2026-10-11-built-in-shiori-cleanup.md)
for executed checks, review resolutions and device evidence.
