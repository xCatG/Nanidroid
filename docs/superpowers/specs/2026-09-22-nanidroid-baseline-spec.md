# Nanidroid rewrite baseline spec (from the 2012 app)

Status: approved product scope with the design's explicit clarifications. Replaces the "behavioral floor" of
`2026-09-22-blind-recreation-design.md` as the source of product scope.

Source: the original 2012 app (last updated 2014), about 9k lines, plus its
bundled first-ghost archive. Where the ghost format itself is concerned, UKADOC
(SakuraScript, SHIORI/3.0, surfaces.txt, descript.txt) is the external
reference; this spec only records what Nanidroid supports and where it
deliberately differs.

Size guard: if this document grows past about 1,200 lines (the size of the 2012
script player and surface parser combined), scope is creeping again.

## 1. Product

A single full-screen "desktop mascot" stage on Android. One ghost is active at a
time: two characters (sakura on the right, kero on the left) are drawn over
the device wallpaper, each with a text balloon above it. A SHIORI engine
produces SakuraScript in response to events; the app plays that script (text,
surface changes, waits, choices, input) and sends back user interactions.

## 2. Screens and flows

| 2012 element | 2012 behavior | Rewrite |
| --- | --- | --- |
| Stage | Wallpaper background; sakura bottom-right, kero bottom-left; balloons in the space above. Startup shows a "Loading Ghost <name>…" progress text until the ghost is ready. | Keep. Compose stage. |
| Control bar | Tapping empty stage or balloon toggles a bar: List Ghosts, Update, Setup, Help. | Keep as a toggled bar or bottom sheet: **Ghosts**, **Help/About**. Drop Update and Setup (Setup only held the analytics toggle). |
| Ghost list | Dialog of installed ghost directory names; pick one → readme dialog (if `readme.txt`) or "switch to ghost?" dialog. Button: More Ghosts. | Keep. Show display name (`name` from descript) rather than directory name. |
| Readme dialog | Renders `readme.txt` (UTF-8 if BOM, else Shift_JIS) as preformatted text. Buttons: Close, Switch to Ghost. | Keep. |
| More Ghosts | Enter URL / Install from SD card (`/sdcard/nar/*.nar|*.zip`, pick if several) / Ghost Town (not implemented). | Replace with one action: **Import .nar** via the system document picker. Drop URL download and Ghost Town. |
| Input box | `\![open,inputbox,ID]` pauses the script and shows a modal text box (OK/Cancel). | Keep as a modal. |
| Choice | `\q[label,ID]` choices shown in a non-cancelable modal list. | Show choices **inline in the balloon** as tappable items. (This was the original design intent.) |
| Help menu | General Usage (links to blog posts), About, Provide Feedback (blog URL). | About only (version, licenses for the native engines). Old blog URLs are dead. |
| Links in balloon | All URL-like text in balloons was auto-linked. | Keep http/https/mailto only; open on tap. |
| Back | Sends OnClose, plays the reply, then finishes. | Keep. |
| Debug bar | Next surface, collision boxes, dump surfaces, test scripts (debug builds). | Optional: a debug-only collision overlay is worth keeping. |

Dropped entirely: Google Analytics and ACRA, ads, billing/accounts
permissions, the debug view inspector, the background service (URL download,
ghost network update, SSTP/bottle sensors), the preference screen,
`VIEW` intent for `http(s)`/`file` `.nar` URLs.

No `VIEW`/`SEND` intent handling: import is only through the in-app picker.

## 3. Ghost package

Installed layout (2012: `getExternalFilesDir()/ghost/<dir>`; rewrite: app-private
`filesDir/ghost/<dir>`):

```
<dir>/install.txt, readme.txt (optional)
<dir>/ghost/master/descript.txt   + SHIORI files
<dir>/shell/master/descript.txt   + surfaces.txt + surface*.png
```

- **Ghost identity** is the install directory name. It keys last-run ghost and
  per-ghost boot count.
- A directory is listed only if `ghost/master/descript.txt` parses.
- Only `shell/master` is used (no shell switching).

**descript.txt / install.txt parsing**
- If the first line (after a UTF-8 BOM) is `charset,<name>`, decode the file
  with that charset; otherwise Shift_JIS. (Rewrite: treat Shift_JIS as CP932.)
- Every line of the form `key,value` is kept. Lines without a comma are
  ignored.
- 2012 dropped any line with more than one comma. The rewrite splits on the
  first comma, so values may contain commas.
- Keys used: `name`, `sakura.name`, `kero.name`, `craftman`/`craftmanw`,
  `shiori`, `id`, `type`, `directory` (install.txt).

## 4. SHIORI

**Request** (one per event), CRLF line endings, blank line terminator:

```
GET SHIORI/3.0
Sender: Nanidroid
ID: <event>
SecurityLevel: local
Reference0: <value>        (one line per reference, in order)
```

Satori receives an additional `Charset: Shift_JIS` header, and the whole request
is encoded in Shift_JIS. For YAYA, query the transport charset before
encoding (see §11).

**Response**: the status line is `SHIORI/x.y <code> <text>`. Header lines are
`Key: value`. Only code 200 with a `Value` header produces script. If a
`Charset` header is present, the response bytes are decoded with that charset.
In the rewrite, parse on the first `: ` or `:`, and a missing or garbled
status line counts as "no script".

**Engine selection** (from `ghost/master/descript.txt` `shiori`):

| `shiori` value | 2012 | Rewrite |
| --- | --- | --- |
| `Nanidroid` | Built-in table SHIORI | Keep (pure Kotlin, §4.1) |
| `satori.dll` | Satori (native) | Keep |
| `shiori.dll` + `kawarirc.kis` present | Kawari 8 (native) | Keep |
| `shiori.dll` + `kawari.ini` | Unsupported | Unsupported |
| YAYA config `yaya.txt`, or a DLL containing the bytes `yaya.dll` with a matching same-stem `.txt` | Unsupported | **Supported** through retained YAYA loader discovery |
| AYA5-only DLL/config without a YAYA marker or `yaya.txt` | Unsupported | Unsupported; use the app-provided unsupported SHIORI |
| anything else / missing | Unsupported | Unsupported |

**Unsupported engines**: an app-provided fallback answers OnBoot, OnFirstBoot,
and OnGhostChanged with "this ghost uses a SHIORI Nanidroid does not support
yet". It answers OnGhostChanging with "Changing ghost to %s" and OnClose with
a thank-you. Keep this so that an unsupported ghost can still be switched away
from.

### 4.1 Built-in "Nanidroid" SHIORI

It is used by the bundled first ghost.
- It loads `ghost/master/<locale language>/content.txt`, falling back to `ja/`.
  The file is UTF-8, `event,script` per line, and `;` starts a comment.
- OnGhostChanging and OnGhostChanged substitute Reference0 into `%1$s`.
- If there is no OnClose entry, it answers `OnClose`. Any unknown event gets
  204.
- The responses carry `Charset: UTF-8`.

The bundled ghost (`assets/nanidroid.zip`) contains `install.txt`, `readme.txt`,
descript files, `en/` and `ja/` content, and `surface0000.png` / `surface0010.png`.
It also contains `homeurl`, which was used only by the dropped Update feature.

## 5. Events sent

| Event | When | References |
| --- | --- | --- |
| OnFirstBoot | First ever activation of this ghost | `0` |
| OnBoot | Later activations | shell name (`master` if none) |
| OnClose | Back pressed | — |
| OnGhostChanging | Before switching, to the outgoing ghost | next sakura name, `manual`, (empty), next ghost path |
| OnGhostChanged | After switching, to the incoming ghost (already-used ghost only; first use gets OnFirstBoot) | previous ghost name |
| OnSecondChange / OnMinuteChange | Each second / minute while resumed | OS uptime in whole hours, `0`, `0`, `1` |
| OnSurfaceChange | Script changes a surface | sakura surface id, kero surface id |
| OnMouseClick / OnMouseDoubleClick / OnMouseMove | Touch on a character | x, y, `0` (wheel), `0`=sakura/`1`=kero, collision id or empty, button id, `touch` |
| OnChoiceSelect | Choice tapped | choice id |
| OnUserInput | Input box OK | box id, text |
| OnInstallBegin / OnInstallComplete | Import start / success, to the current ghost | `ghost`, dir, dir |
| OnInstallRefuse | Import target already installed | — |
| OnInstallFailure | Import could not be read or extracted | — |

Coordinates are in surface pixels (unscaled). 2012 also defined OnMouseWheel,
OnWindowStateMinimize, and OnWindowStateRestore but never triggered them; the
rewrite drops them.

OnSecondChange/OnMinuteChange Reference0 is OS uptime in whole hours, per UKADOC
(2012 sent hours since the stage started). Paused time sends nothing, and missed
ticks are not replayed.

## 6. SakuraScript subset

**Correction approved 2026-09-26 for milestone 4.** A case-sensitive `On`-prefixed
choice ID dispatches that event directly, with comma fields after the ID as
Reference0 onward; ordinary IDs still dispatch `OnChoiceSelect(ID)` and ignore
extra fields. Reject control characters in dispatched IDs and references.
The unbracketed numeric `\bN` form consumes its digit and keeps the bracketed
and hide behavior below. Supported tags adjacent to Japanese prose preserve
that prose, including `\xそれでね、` and `\n日付[2026]`.

Playback runs one step per 50 ms tick by default, appending one character per
step. Supported in 2012 (rewrite keeps all of these):

| Tag | Effect |
| --- | --- |
| `\0` `\h` | Switch to sakura (clears sakura balloon if switching from kero) |
| `\1` `\u` | Switch to kero, clear kero balloon |
| `\s0`–`\s9`, `\s[N]`, `\s[-1]` | Set current character's surface; `-1` hides it |
| `\i[N]` | Play animation N on current character (overrides talk animation) |
| `\b[N]` / `\_b[...]` | Balloon id; only effect is `-1` hides the balloon |
| `\n` | Newline; `\n[half]`, `\n[NN%]` treated as plain newline |
| `\c` | Clear current balloon |
| `\w1`–`\w9` | Wait N × 50 ms |
| `\_w[ms]` | Wait ms |
| `\_q` | Toggle quick mode (no per-character delay) |
| `\_s` | Toggle sync (text goes to both balloons) |
| `\e` | End script (1 s pause, then next queued script) |
| `\q[label,ID]` | Choice (extra fields after ID ignored) |
| `\![open,inputbox,ID]` | Pause and open input box |
| `%username`, `%selfname`, `%selfname2`, `%keroname` | Replaced before playback (`%username` = "User") |

The following are silently consumed: `\-`, `\4`, `\5`, `\6`, `\v`, `\_n`, `\_V`,
and `\_l[..]`, `\_a[..]`, `\_v[..]` together with their bracket argument. The
rewrite also ignores any other unknown tag *with* its bracket argument. 2012
leaked `[...]` text of unknown tags into the balloon.

Script queueing: responses to events are appended to one FIFO queue and played
in order.
- A choice selection, or a touch on kero, clears the queue before sending the
  event. The rewrite does this for touches on either character (2012 did it
  for kero only).
- Balloons hide when the queue drains.

## 7. Surfaces and animation

**Correction approved 2026-09-26 for milestone 4.** Legacy alternative records
such as `333pattern0,0,0,alternativestart,[4.5.6]` take their target IDs
from the field after `alternativestart`; modern records retain their authored
target field. Malformed target lists are skipped. For each eligible second,
`sometimes` starts on a 1/2 roll and `rarely` on a 1/4 roll, independently.
This supersedes the earlier equal 25% rule below.

**surfaces.txt** (Shift_JIS, or honor a `charset` line):
- `surfaceN` or `surfaceN,surfaceM,...` followed by `{ ... }`. The block
  applies to every listed id.
- Inside a block:
  - `collisionK,x1,y1,x2,y2,Name`: rectangle.
  - `elementK,method,file,x,y`: composite base from element files.
  - `KintervalN,type`, `animationK.interval,type`
  - `KpatternN,surface,wait,method,x,y`, plus `animationK.patternN,method,surface,wait,x,y`
    - The method is `overlay` or `overlayfast`, `base`, or `move`.
    - A surface value of `-1` resets to the base surface.
  - `alternativestart,[a.b.c]` / `alternativestart,(a,b,c)`: random pick among
    patterns.
  - `point.*`, `KOption,...`: parsed and ignored.
- Lines beginning with `//` or `;` are comments.
- Every `surface<N>.png` or `surface<NNNN>.png` in the shell folder is a
  surface even if surfaces.txt doesn't list it. Numbers are normalized, so
  `0010` becomes `10`.

**Defaults**: sakura starts on surface 0, kero on 10. An unknown sakura id falls
back to 0, and an unknown kero id falls back to 10.

**Transparency**: the top-left pixel colour is transparent. The rewrite applies
this only to images without an alpha channel; it keeps alpha where present and
honors `.pna` masks per UKADOC.

**Animation intervals**:
- `sometimes` and `rarely`: each second, each character rolls once. There is a
  25% chance to start a `rarely` animation and a 25% chance to start a
  `sometimes` animation.
- `talk`: starts while text is appearing, at most once every 10 ticks, unless
  an explicit `\i` is active.
- `always`, `runonce`, `random`, `yen-e`, `bind`, `never`: recognized in 2012
  but not executed. Rewrite: implement `always` and `runonce`; the rest remain
  unsupported.
- `move` frames were unsupported in 2012. The rewrite implements `move` per
  UKADOC.

**Hit testing**: a touch on a character's view is tested against that surface's
collision rectangles in surface pixel space. The first match gives the
collision id; no match gives an empty id.

## 8. Layout

2012 rule (keep the shared fit and placement baseline):
- Scale both characters by one factor so that sakura width plus kero width
  fits the screen width, and the taller of the two fits the height.
- Sakura is anchored bottom-right, kero bottom-left.
- If kero is less than half sakura's height, kero's balloon sits directly above
  kero and sakura's balloon uses the full width above sakura. Otherwise, each
  balloon takes half the width in the space above the characters.
- Balloons scroll; new text auto-scrolls to the bottom.

The rewrite adds insets and edge-to-edge. It also handles landscape and large
fonts, but any approach that keeps both characters and both balloons visible
is fine; this is not a pixel spec.

The author's 2026-09-29 logical artwork choice supersedes the 2012 no-upscale
cap for this recreation. Treat authored pixels as artwork at 320 dpi (2 px/dp).
Choose a target ratio from the shorter side of the current, unpadded Activity
window configuration: below 400 dp → 1×, 400 to below 600 dp → 1.5×, and
600 dp or more → 2×. Convert that ratio once by current Compose density / 2,
then cap the one shared scale by combined character width and available stage
height after the existing font-aware balloon reserve. Anchors, authored offsets,
and inverse touch mapping continue to use that final shared scale. These bucket
boundaries implement the approved compact/tall/tablet preview, not measured
optimal device breakpoints; Milestone 5 device acceptance remains open.

## 9. Lifecycle

**Startup**:
1. If no ghosts are installed, install the bundled `nanidroid` ghost.
2. Load the last-run ghost, defaulting to `nanidroid`.
3. Increment that ghost's boot count.
4. On the app's very first launch, queue the bundled first-run script
   (write new text; the 2012 text described the old control bar).
5. Start the clock and send OnFirstBoot or OnBoot.

**Pause / resume**: the clock stops on pause and restarts on resume. 2012
re-sent OnBoot on every resume. The rewrite sends it once per process start
of the ghost, not per resume.

**Switch**:
1. Clear the queue and send OnGhostChanging to the current ghost.
2. When its script finishes, unload the old SHIORI.
3. Load the new ghost and remember it as the last-run ghost.
4. Send OnGhostChanged or OnFirstBoot to the new ghost.

If loading fails, show an error and activate the bundled Nanidroid ghost, which
cannot fail a native load (2012 left the app with no ghost).

**Exit**: Back sends OnClose. The activity finishes when that script ends.

## 10. Import (.nar)

2012 behavior:
- Open the zip and find the shallowest `install.txt`. If it is inside a
  top-level folder, strip that folder level on extraction.
- Read `directory`. If that directory is already installed, send
  OnInstallRefuse. Otherwise, send OnInstallBegin, extract all entries into
  `ghost/<directory>`, and send OnInstallComplete.
- Then show the readme dialog, or the "switch to it?" dialog if there is no
  readme.
- Installation never activates the ghost by itself.

Rewrite:
- **Source and scope**: the document picker (`content://`), one import at a
  time, foreground only.
- **Validation**: accept only `type,ghost`. Also reject:
  - entry paths that escape the target (`..`, absolute paths, drive letters)
  - symlinks
  - a missing `directory`
  - totals over a size/entry cap
- **Staging**: extract into a private staging directory, then rename to
  `ghost/<directory>`. Staging is wiped at app start.

This fixes 2012 bugs: zip-slip, temp files on `/mnt/sdcard/nar`, non-ghost
archives installed anyway, and partial installs left behind.

## 11. Native layer

Copy `jni/**` from the revision pinned in the native copy manifest (not from the
2012 app): it
contains YAYA, the hardened Kawari/Satori bridges, and the CMake build. Take
the JNI method signatures from that revision, then write new thin Kotlin
wrappers with the same class names (`com.cattailsw.nanidroid.shiori.Kawari`,
`SatoriShiori`, `YayaShiori`) because the bindings depend on them. All
native calls run on one dedicated thread; only one native ghost is loaded at a
time.

## 12. 2012 behavior deliberately not preserved

- **Touch events**: every touch event (down, move, up) fired
  OnMouseDoubleClick. Rewrite: tap → OnMouseClick, double tap →
  OnMouseDoubleClick, drag → OnMouseMove (throttled).
- **OnSurfaceChange references** were double-prefixed
  (`Reference0: Reference0: 0`). Rewrite sends bare ids.
- **`%selfname` replacement** used a regex replace, so ghost names containing
  `$` or `\` broke it.
- **Choices blocked the stage in a modal.** Rewrite shows them inline (§2).
- **Script state was lost or duplicated on configuration changes.** The rewrite
  owns one runtime per app, and the UI observes its state.
- **External storage requirement** and "No SD card" hard exit.

## 13. From the 2026 modernization, keep only

- The native layer (§11) and the platform baseline (minSdk 31, compile/target
  37, arm64-v8a + x86_64, scoped storage, edge-to-edge).
No 2026 regression list is carried over. Real ghosts are tested against the
new code, and a failure found there becomes a new fixture and test at that time.

## 14. Milestones

1. **Walking skeleton**: bundled ghost with the built-in SHIORI (no native
   code). Boot, text playback, surfaces, and touch → OnMouseClick all work on a
   device.
2. **Native engines**: Satori, Kawari, and YAYA ghosts boot and talk. Switch
   and exit flows work.
3. **Import**: the import flow with the validation rules in §10.
4. **Surfaces**: animation (`sometimes`/`rarely`/`talk`/`always`/`runonce`,
   `move`), choices, input box, links.
5. **Polish**: corpus pass over whatever NARs are available locally, then
   landscape/large-font polish.

Each milestone is usable on its own. Stop and reassess if a milestone takes
more than twice its estimate.
