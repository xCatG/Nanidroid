# Milestone 5 corpus inventory

Observed 2026-09-27. Data-only inspection and SHA-256 hashing; no app execution is claimed.

Source root: `C:/tmp/Nanidroid-corpus-recovery`.

22 NAR files with distinct hashes: 18 ghost archives spanning eight families, three shells and one balloon. Multiple versions remain separate inputs. `2elf.zip` adds one wrapper negative case, not another ghost. Its sole embedded NAR hashes to `A50830E18DEF75BE051A3638C7375C7E2D96CB18F7B3F26D0037D84A0FC20BE0`, identical to the extracted 2elf NAR.

Descriptors below are input hints; the implementation task must confirm package-root policy and actual engine dispatch with the fresh reader. No old audit classifications or compatibility exceptions are imported.

| Relative path | Bytes | Kind / engine hint | SHA-256 |
| --- | ---: | --- | --- |
| `2elf.zip` | 2342410 | wrapper | `EFAB05886BA48FD333666F28033B75B7D8FF055951066A1043C2D96ED07C9ED9` |
| `2elf/2elf-2.46.nar` | 2405498 | ghost satori.dll | `A50830E18DEF75BE051A3638C7375C7E2D96CB18F7B3F26D0037D84A0FC20BE0` |
| `pcPets/Ukagakas/Big Red Button/big_red_button.nar` | 303443 | ghost shiori.dll (Kawari config present) | `36AD0500958D88175D9E2530F4AA6E085A2D8579BBB200C1E2D2F9AC0785D21D` |
| `pcPets/Ukagakas/Earthquake Rescue Duo/Earthquake_duo_1.0.1.nar` | 1327659 | ghost yaya.dll | `06DB71E7E8293B4AF0B5127DD73402D4ED90FECC5FDCEBF4F0D34337CCB66538` |
| `pcPets/Ukagakas/Haiidrate/haiidrate.nar` | 393656 | shell | `BA7F9B6D191A47491721892CE69CE4FA7CD8DBE61C8CBF28A23EDE666937B685` |
| `pcPets/Ukagakas/Hareraiser/hareraiser_balloon.nar` | 140936 | balloon | `A686E3B4C57F30985582FB8FFE9CBBFDA49609FA046BB8CA08B003105E1FE7FE` |
| `pcPets/Ukagakas/Kitsune no Ocha/kitsune-no-ocha.nar` | 681017 | shell | `7B74CBABA0F2B0B159FB20DA194D51C07925BB6C208C97E5021879FBDC3D29F5` |
| `pcPets/Ukagakas/LOBO/LOBO_1.0.0.nar` | 908940 | ghost shiori.dll (Kawari config present) | `F4E90615CF40801D4A7A7170762B6C0D6DDDF18324F9BA146F4A700CBE2BEBF7` |
| `pcPets/Ukagakas/Nanika Atsume/Nanika_Atsume_1.0.0.nar` | 4127585 | ghost shiori.dll (Kawari config present) | `0DDFE156BF29E36522E58FE113EF64D0423CFD841007901A941DDA50ED3302F9` |
| `pcPets/Ukagakas/Nanika Atsume/Nanika_Atsume_1.0.1.nar` | 4127826 | ghost shiori.dll (Kawari config present) | `9B5FFC161ABC489BCE332702A1945F3F7D5EC6D66DEF3B521299FF36D91F290C` |
| `pcPets/Ukagakas/Nanika Atsume/Nanika_Atsume_silent_ALPHA.nar` | 4126294 | ghost shiori.dll (Kawari config present) | `BE187FB6F51E3B45B5CFA0AB07A8FE46FD6862146A82E8E9DAB563E699BF5D17` |
| `pcPets/Ukagakas/Snake and Otacon/Snake and Otacon V1.0.0.nar` | 1219714 | ghost aya5.dll | `526B7721103031FB3F28B22FFFC54B71FD0B1E279168934A06D8076E20A1CBCC` |
| `pcPets/Ukagakas/Snake and Otacon/Snake and Otacon V1.0.1.nar` | 1189933 | ghost aya5.dll | `6F44DD039C17093D3F91E47BB9C474E128EB34FA4BFEB5EF3148625BBD613764` |
| `pcPets/Ukagakas/Snake and Otacon/Snake And Otacon V1.1.1.nar` | 1218239 | ghost aya5.dll | `21253507C17E90073974229DDF8B0D39E36EFCAE968A27C2569FE5C46C201E4B` |
| `pcPets/Ukagakas/Snake and Otacon/Snake and Otacon V1.2.1.nar` | 10027963 | ghost yaya.dll | `A4B89D1C932F5862CA60E8BACF62563DADB65F4DADCE5FD1BC7945DB652ACB6F` |
| `pcPets/Ukagakas/Snake and Otacon/Snake and Otacon V1.3.1.nar` | 9545820 | ghost yaya.dll | `A710FF1F031FFD23D7D61FCF7FABED5D1CB4794EAF06E9EB6CD9D6DF5FCC1219` |
| `pcPets/Ukagakas/Snake and Otacon/Snake and Otacon V1.3.2.nar` | 10131190 | ghost yaya.dll | `1C62CE50CA0DACA3A9E14E6D870B02D4DF9511DD5B586A7F4DA49B402D56CBD5` |
| `pcPets/Ukagakas/Snake and Otacon/Snake_Otacon_1.1.1b.nar` | 1201661 | ghost aya5.dll | `EF1590F766964B1932020ABF6E93AA229BE12FBC6BA9238A4E5CDA90939F4D70` |
| `pcPets/Ukagakas/Snake and Otacon/Snake_Otacon_1.2.1b.nar` | 2252837 | ghost aya5.dll | `4C925DC0B8A61B41CC91C72589E30E4ECE7E6B0B92DCC44EEC993B71605AED45` |
| `pcPets/Ukagakas/Snake and Otacon/Snake_Otacon_1.3.1b.nar` | 9185887 | ghost yaya.dll | `04D7563D65116D14E9E1208586C77CF3A6703DFCC3C10D48A10D581CFA9B8B59` |
| `pcPets/Ukagakas/The Petpet Puddle/hydrate-petpet-puddle-1.0.0.nar` | 117950 | shell | `7746F4F47B633FF940200859052D351FB4D68BCE307425AF42CDDBD1B9DCCB22` |
| `pcPets/Ukagakas/Watchdog Bancho/bancho_jet_v1.0.0.nar` | 1502629 | ghost shiori.dll (Kawari config present) | `8A3F1DCAA4C34A625BF16C0A0ADA2E3DFF2D49FC029E014807AAFB164F196DCA` |
| `Yes_Man-2.1.1.nar` | 51419300 | ghost yaya.dll | `AA6383F564FC2D89CBBC926CD672F481D2E8AAFA48EC235B07BA0CBDF77912E8` |

Original archives were not modified or copied into Git. File availability does not establish importability, safe execution, protocol coverage or persisted state. See the dedicated [Milestone 5 plan](../superpowers/plans/2026-09-27-milestone-5-corpus-polish.md) for execution and acceptance.

## Task 1 preflight and reader observations — 2026-09-27

All 23 paths above existed at the source root and were independently rehashed before device execution. Every byte length and SHA-256 matched this inventory; there were no missing or changed inputs. The frozen machine-readable manifest is [milestone-5-corpus.json](milestone-5-corpus.json). The 22 NAR rows are 18 ghosts, three shells and one balloon; `2elf.zip` is the separate wrapper negative row. No archive was downloaded or added to Git.

The archive entry inspection found root-level `install.txt` in each of the 22 NARs, so the candidate package root is empty for those inputs. The wrapper has no top-level ghost `install.txt`. The current `NarArchiveReader.inspect`/`GhostImporter` device path is the authority for acceptance: 18 NARs declare `type,ghost`, three `type,shell`, one `type,balloon`; shell, balloon and wrapper rows were rejected by the actual importer in the Task 1 run. For successfully imported ghosts, the device test records the fresh reader's validated directory ID and `InstalledGhostRepository` descriptor/engine declaration in each `corpus-result.json`. Rejected root-entry files have no reader-validated metadata, so their declaration and root are data-only inventory observations, not a claim of successful reader dispatch.

Four Snake and Otacon archives contain the exact root-only ZIP directory record `\`: `V1.0.0`, `V1.0.1`, `V1.1.1`, and `1.1.1b`. The current reader rejects this before validation or publication. The Task 1 test fixture exercises the same importer with one benign root record and six hostile variants. The benign case is red pending the planned Task 2 reader correction; the root-named file, nonempty root record, symlink record, absolute child, drive path and traversal all reject.

The one targeted dictionary read for `Nanika_Atsume_silent_ALPHA.nar` was `ghost\\master\\template\\ghost-keeps-bootend.kis` (Shift_JIS decoded from the unchanged archive). It contains authored `TalkFirstboot` text `Welcome to Nanika Atsume!`; the device first-boot playback also reached that text. Thus the filename's `silent_ALPHA` suffix cannot be treated as evidence that first boot should be silent. This pass made no dictionary trigger hunt. Any other authored silence condition remains unverified and must be stated as such in follow-up evidence.
