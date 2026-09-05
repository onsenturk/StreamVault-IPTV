# StreamVault — Optimization Backlog

Follow-up work to the build/CI hardening on `copilot/check-repo-for-improvements`.

Everything already landed on that branch (PR validation workflow, Gradle parallel/build-cache,
lint-baseline ratchet, packaging excludes, release native symbols, untracking committed build
logs) is **done** and is not repeated here.

## Why these items were deferred

The agent session that produced this list ran in a sandbox where `dl.google.com` is unreachable,
so the Android Gradle Plugin could never resolve and **no Gradle build, test, or lint task could
be run**. Every item below needs a compiler to verify. They were deliberately left undone rather
than attempted blind against a ~240k-LOC codebase.

You have a working toolchain, so the constraint is gone. Each item lists the verification command
that was unavailable.

All counts below were verified against the tree at commit `a4b6c89`.

**Baseline check before starting anything:**

```bash
./gradlew testDebugUnitTest \
  :app:lintDebug :data:lintDebug :player:lintDebug \
  verifyLintBaseline \
  koverXmlReportCi koverHtmlReportCi
```

---

## Priority 1 — High value, low risk

### 1.1 Split `Daos.kt`

**Where:** `data/src/main/java/com/streamvault/data/local/dao/Daos.kt` (4,082 lines, 24 `@Dao` types)

**Why:** This is the single best build-time win available. Kotlin and KSP work at file
granularity for much of their incremental analysis, so a one-line change to any DAO currently
invalidates a 4,082-line compilation unit *and* re-runs Room's annotation processing for all 24
DAOs. It is also a pure file-move refactor — no logic changes, so the risk is close to zero.

The codebase already has the target convention: the same directory contains 17 per-domain DAO
files (`DownloadDao.kt`, `RecordingDaos.kt`, `SearchDao.kt`, `StalkerSyncDaos.kt`,
`XtreamIndexDaos.kt`, …). `Daos.kt` is the legacy monolith that predates it.

**Current contents, with suggested grouping:**

| Target file | DAOs |
|---|---|
| `ProviderDaos.kt` | `ProviderDao`, `SyncMetadataDao` |
| `ChannelDaos.kt` | `ChannelDao`, `ChannelPreferenceDao`, `CategoryDao`, `VirtualGroupDao` |
| `VodDaos.kt` | `MovieDao`, `SeriesDao`, `EpisodeDao`, `TmdbIdentityDao`, `VodCatalogEntryDao` |
| `VodHydrationDaos.kt` | `MovieCategoryHydrationDao`, `SeriesCategoryHydrationDao`, `VodCategoryHydrationDao` |
| `EpgDaos.kt` | `ProgramDao`, `EpgSourceDao`, `ProviderEpgSourceDao`, `EpgChannelDao`, `EpgProgrammeDao`, `ChannelEpgMappingDao` |
| `UserActivityDaos.kt` | `FavoriteDao`, `PlaybackHistoryDao`, `SearchHistoryDao`, `PlaybackCompatibilityDao` |

**Notes:**
- The three projection data classes at the top of the file (`RemoteIdMapping`,
  `SeriesRemoteIdMapping`, `TmdbIdMapping`) are shared. Give them their own
  `DaoProjections.kt` rather than duplicating them.
- The file uses star imports (`androidx.room.*`, `com.streamvault.data.local.entity.*`).
  Let the IDE resolve explicit imports per new file; do not carry the star imports over.
- Six of the 24 are `abstract class`, not `interface` (`ProviderDao`, `ChannelDao`,
  `FavoriteDao`, `VodCatalogEntryDao`, `ProviderEpgSourceDao`, `ChannelEpgMappingDao`) —
  they have concrete `@Transaction` bodies. Move them whole.
- Do this as **one commit containing only moves**, so the diff is reviewable and a
  `git diff -M` shows pure renames.

**Verify:**
```bash
./gradlew :data:kspDebugKotlin :data:compileDebugKotlin
./gradlew :data:testDebugUnitTest
./gradlew :data:connectedDebugAndroidTest   # migration tests
```

**Acceptance:** identical generated Room code, all `:data` tests green, no change to
`data/schemas/`.

---

### 1.2 Enable Gradle configuration cache

**Where:** `app/build.gradle.kts`

**Why:** Left off deliberately in the previous change — `gradle.properties` carries a comment
explaining this. It is the largest remaining build-time win, but it needs three specific
blockers fixed first.

**Blockers, all in `app/build.gradle.kts`:**

| Line(s) | Problem |
|---|---|
| 16–19, 22–25 | `rootProject.file(...)` + `FileInputStream` read `keystore.properties` and `local.properties` at configuration time |
| 28, 103–108 | `localProp(...)` feeds `buildConfigField` from that configuration-time read |
| 30–48 | `computeOfficialSigningCertSha256()` opens the JKS keystore at configuration time |
| 115 | `System.currentTimeMillis()` in the `beta` build type makes configuration non-deterministic |

**What to do:** move each read behind `providers.fileContents(...)` / `providers.of(...)` so the
values become lazy `Provider`s, and derive the beta timestamp from a `ValueSource` or an injected
Gradle property (CI can pass `-PbuildTimestamp=...`) instead of calling the clock during
configuration.

**Then flip it on** in `gradle.properties` and remove the explanatory comment:
```properties
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=fail
```

**Verify:**
```bash
./gradlew --configuration-cache :app:assembleDebug
./gradlew --configuration-cache :app:assembleDebug   # must report "reused"
./gradlew --configuration-cache :app:assembleRelease
```

**Acceptance:** second invocation reports configuration cache reuse with zero problems; a release
build with `keystore.properties` present still signs correctly and
`BuildConfig.OFFICIAL_SIGNING_CERT_SHA256` is still populated.

---

### 1.3 Fix the FFmpeg AAR cross-module path

**Where:** `app/build.gradle.kts:225` — `implementation(files("../player/libs/media3-decoder-ffmpeg-1.9.2.aar"))`

**Why:** `:app` reaches across a raw relative filesystem path into `:player`'s directory. Meanwhile
`:player` already owns this artifact properly: `player/build.gradle.kts:45-118` defines
`verifyLocalFfmpegArtifact`, which validates the media3 version, both ABIs' `.so` files, the
expected classes, and the LGPL-relevant codec set — and wires it into `preBuild`.

So `:app` consumes the artifact while bypassing the module that verifies it. If the AAR is ever
renamed or version-bumped, two files must change in lockstep and the `:app` one fails with an
opaque missing-file error.

**What to do:** have `:player` expose the AAR to its consumers (an `api` file dependency, or a
proper flatDir/local Maven repo entry keyed off the version in the catalog) and delete the
`files(...)` line from `:app`. Confirm the native `.so` files still land in the APK.

**Verify:**
```bash
./gradlew :app:assembleRelease
unzip -l app/build/outputs/apk/release/*.apk | grep -E 'libffmpegJNI|libavcodec'
```

**Acceptance:** both `arm64-v8a` and `armeabi-v7a` FFmpeg libraries present in the APK; FFmpeg
audio playback still works on device.

---

## Priority 2 — Behavioural, needs on-device verification

### 2.1 ProGuard log stripping — verify the payoff before doing this

**Why it looked attractive:** 275 `Log.*` call sites ship in release builds (`:data` 179,
`:app` 48, `:player` 48; `:domain` 0, and zero `println`). `app/proguard-rules.pro` has no
`-assumenosideeffects` rule, so all of them survive R8 along with their string-building.

**Caveat that made this unsafe to do blind:** `player/.../playback/PlaybackLogSanitizer.kt` exists
specifically to sanitize playback log output, and `AGENTS.md` documents a live-TV validation
procedure that greps `adb logcat` for markers such as `first-frame-success`,
`live-recovery selected`, `retry category=`, and `prepare resolvedStreamType=`. Stripping those
would silently break the project's own debugging workflow.

**What was verified since:** all four of those markers are emitted above the strippable levels —
`first-frame-success` at `Log.i` (`Media3PlayerEngine.kt:1649`), `prepare resolvedStreamType` at
`Log.i` (`Media3PlayerEngine.kt:1080`), `retry category=` at `Log.w`
(`Media3PlayerEngine.kt:2449`), `live-recovery selected` at `Log.i`
(`PlayerAlternateStreamActions.kt:57`). So a `Log.v`/`Log.d`-only strip is safe for the
documented workflow.

**But the payoff is small.** Only **32 of the 275** calls are `Log.d`/`Log.v` (`:data` 23,
`:player` 6, `:app` 3) — about 12%. The remaining 243 are `Log.i`/`Log.w`/`Log.e`, which should
not be stripped.

**Recommendation:** measure before committing to this. Add the `-assumenosideeffects` rule for
`Log.v`/`Log.d` only, build a release APK, and compare size against the current one. If the delta
is negligible — which 32 call sites suggests — drop the item rather than adding a ProGuard rule
that constrains future logging for no measurable benefit. Do **not** extend it to `Log.i`/`Log.w`.

**Verify:** release APK size delta, then the full live-TV validation from `AGENTS.md`
(2-second screenshot cadence, ≥61 frames ≈ 2 minutes, more than one channel) confirming every
marker is still greppable.

---

### 2.2 Audit `!!` assertions

**Why:** 67 in production code (`:data` 41, `:app` 25, `:player` 1, `:domain` 0). On a TV device
a `NullPointerException` is disproportionately costly — there is no easy recovery from the couch.

**Densest files:**

| File | Count |
|---|---|
| `data/.../sync/VodCategoryHydrationCoordinator.kt` | 11 |
| `data/.../parser/XmltvParser.kt` | 7 |
| `data/.../remote/stalker/OkHttpStalkerApiService.kt` | 5 |
| `app/.../ui/screens/series/SeriesScreen.kt` | 5 |
| `app/.../ui/screens/movies/MoviesScreen.kt` | 5 |
| `data/.../manager/BackupManagerImpl.kt` | 4 |

The two `*Screen.kt` clusters are dialog-state reads of the
`uiState.selectedXForDialog!!` shape — mechanical to replace with a `?.let` or a sealed UI state
that makes the dialog-open case structurally non-null. Start there; they are the lowest-risk and
most obviously correct.

The parser ones (`XmltvParser`, `OkHttpStalkerApiService`) are the highest-value: they run against
untrusted remote data, where a malformed provider response is exactly the input that triggers them.

**Acceptance:** no new crashes; prefer changes that make nullability structurally impossible over
adding `?:` fallbacks that mask real parse failures.

---

### 2.3 Audit broad exception handling

**Why:** 98 `catch (e: Exception)` blocks in production, heavily concentrated in `:data` (84;
`:app` 14, `:player` 0). Not wrong by itself on provider network paths, but worth confirming each
one logs rather than silently swallowing — a swallowed provider error surfaces to the user as an
empty category with no diagnostic trail.

**What to do:** sweep `:data` for catch blocks with empty or comment-only bodies and ensure each
either logs at `Log.w`/`Log.e` or has a comment justifying the silence. Narrow the caught type
where the failure mode is known (`IOException`, `JsonParseException`).

---

## Priority 3 — Structural, larger effort

These are maintainability rather than correctness work. Sequence them one per PR, each with green
CI, and do not combine with behavioural changes.

| File | Lines | Suggested split |
|---|---|---|
| `data/.../manager/BackupManagerImpl.kt` | 5,777 | `BackupExporter` / `BackupImporter` / `BackupValidator` / `BackupTransformer` |
| `data/.../remote/stalker/OkHttpStalkerApiService.kt` | 4,487 | session+auth / pagination / response parsing |
| `app/.../ui/screens/provider/ProviderSetupScreen.kt` | 3,650 | per-provider sub-composables (also shrinks recomposition scope) |
| `data/.../remote/stalker/StalkerProvider.kt` | 2,631 | catalog fetch vs. parsing |
| `data/.../preferences/PreferencesRepository.kt` | 2,564 | per-feature preference groups |
| `player/.../Media3PlayerEngine.kt` | 2,507 | transport / subtitles / quality / buffering |
| `data/.../sync/SyncManager.kt` | 2,484 | per-content-type sync strategies |
| `app/.../ui/screens/home/HomeViewModel.kt` | 2,135 | 83 functions; split by domain (channels / categories / favorites / multi-view) |
| `app/.../ui/screens/epg/EpgViewModel.kt` | 2,106 | guide data vs. overlay presentation state |

Two cross-cutting opportunities sit behind these: 17 `*RepositoryImpl` classes in `:data` and 16
`*ViewModel` classes in `:app` repeat the same cache/DB/remote and filter/search/paginate shapes.
Extracting shared bases would be a bigger win than any individual split — but only attempt it
*after* the god classes are broken up, or the abstraction will be designed around the wrong seams.

**Note:** `ProviderSetupScreen.kt` has no test file. Add characterization tests before refactoring it.

---

## Priority 4 — Longer-term

### 4.1 Instrumented test coverage for `:player` and `:domain`

Both have **zero** `androidTest` sources (`:player` 35 unit tests, `:domain` 24; compare `:app`
73+12 and `:data` 117+13). `Media3PlayerEngine.kt` is 2,507 lines and playback is the app's core
value, yet `AGENTS.md` shows live-TV correctness is currently validated *manually* via screenshot
cadence and logcat greps. Automating even part of that into `:player` instrumented tests would
convert a documented manual ritual into a CI gate.

### 4.2 Convention plugin for shared module config

`compileSdk = 36`, `minSdk = 25`, `JavaVersion.VERSION_17`, the Kotlin `jvmTarget`, core library
desugaring, the lint baseline block and the Kover `ci` variant are copy-pasted across `:app`,
`:data` and `:player`. A `build-logic` included build (or `buildSrc`) would centralize them and
remove the drift risk. Do this *after* configuration cache (1.2), since convention plugins are
easier to write correctly against provider-based APIs.

### 4.3 Lint baseline burndown

`app/lint-baseline.xml` holds 1,560 suppressed issues across 45 issue types (`:data` 18, `:player`
9). `warningsAsErrors = true` is on, so the baseline is the only thing keeping the build green.

The ratchet added in the previous change (`lintBaselineCeilings` in the root `build.gradle.kts`)
means the backlog can now only shrink — but it will not shrink on its own. Burn it down in themed
batches by issue id, lowering the matching ceiling in the same commit. `verifyLintBaseline` prints
how far each baseline sits below its ceiling to make this easy to spot.

### 4.4 Dependency review

`okhttp` is pinned at `4.12.0`, the last 4.x. A 5.x evaluation may be worthwhile, but treat the
pin as deliberate — the Stalker portal session/cookie handling in `OkHttpStalkerApiService.kt` is
the most exposed consumer and would need real provider testing.

Several media3/okhttp/retrofit/room/coroutines/hilt coordinates are declared in more than one
module. That is not incorrect (each module genuinely uses them), and 4.2 is the cleaner fix.

---

## Suggested order

1. **1.1 Daos.kt split** — mechanical, immediate incremental-build payoff, safe warm-up
2. **1.2 Configuration cache** — biggest remaining build win
3. **1.3 FFmpeg AAR wiring** — small, removes a real fragility
4. **2.2 `!!` in the two `*Screen.kt` files** — quick, user-visible crash reduction
5. **2.1 Log stripping** — only alongside a real device validation run
6. **4.3 Lint burndown** — steady background work, ratchet already enforces the direction
7. **Priority 3 splits** — one per PR, largest first

## Ground rules

- Run the baseline command at the top of this file before and after each item.
- `verifyLintBaseline` fails if a baseline *grows*. If a change legitimately needs a new
  suppression, raise the matching `lintBaselineCeilings` entry in the root `build.gradle.kts` in
  the same commit so it is reviewable.
- Room schema changes: see `docs/DATABASE_MIGRATION_INVARIANTS.md`. Item 1.1 must not alter
  `data/schemas/`.
- Live-TV playback changes: follow the validation procedure in `AGENTS.md`. A successful build,
  install, or single screenshot is explicitly **not** sufficient evidence.
