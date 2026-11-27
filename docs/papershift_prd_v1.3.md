# Product Requirements Document (PRD) & Technical Architecture Brief

**Product Name:** PaperShift
**Target Platform:** Android (Universal / Brand-Neutral) — minSdk 29 (Android 10), targetSdk 37 (Android 17), compileSdk 37.2
**Document Version:** 1.3
**Status:** Approved (2026-11-26)
**Owner:** _TBD_
**Last Updated:** 2026-11-26

---

## 0. Change Log

| Version | Summary |
| :--- | :--- |
| 1.2 | Architecture diagrams, test plan, edge cases added. |
| 1.3 | Fixed technical contradictions and filled gaps found in review: Bing cadence vs. 12h rotation (§5.3); WorkManager policy `KEEP` → `UPDATE` and network constraint scoped to Bing mode (§4.3); battery features mapped to real APIs (§4.4); decode pipeline rewritten around `ImageDecoder` (§4.2); zero-storage claim made precise and testable (§4.1, TC-BING-02); state machine gains WAITING / SKIPPED / NEEDS_ATTENTION (§2.3); home-screen-only scope for v1.0 made explicit; in-app **Shift Now** moved into MVP; "Both" source moved to v1.1; EXIF, HEIF, live wallpaper, device-policy and backup/restore edge cases added; permissions, non-goals, success metrics, accessibility, localization and risk sections added; test matrix extended to Android 16 and OEMs; design tokens translated from Tailwind to Compose/Material 3; vendor-specific terms removed. |
| 1.3 (decisions, 2026-11-26) | Recorded the 9 product decisions in §13: Bing ships in v1.0 as an accepted risk with per-image credit; v1.0 sets **home and lock screen together** (replaces "home only"); dark theme only; "Both" source stays v1.1; Shift Now, Battery Saver skip, backup off, no crash reporting and the 720 px minimum confirmed. Bing image variants verified against the live endpoint. |
| 1.3 (SDK, 2026-11-26) | targetSdk raised from 36 to 37 (Android 17); compileSdk set to 37.2 because Compose 1.13 requires 37.1 or newer; Android 17 added to the device matrix. |

---

## 1. Executive Summary & Vision

**PaperShift** is a lightweight, zero-bloat Android wallpaper rotation utility. Unlike heavy personalization suites that consume storage, collect telemetry, or run intrusive foreground services, PaperShift focuses strictly on one reliable core function: changing the home and lock screen wallpaper on an automated 12-hour cadence using either local device photos or curated Bing daily images.

The application follows a **fetch-process-discard** architecture — reading or downloading an image into memory, center-cropping it to the screen in a single decode step, handing it to the system `WallpaperManager`, and releasing it. PaperShift never writes image bytes to its own storage.

### 1.1 Goals (v1.0)
1. Set-and-forget rotation that a user can configure in under 30 seconds.
2. Zero image bytes stored by the app; minimal, bounded app data.
3. No measurable battery impact attributable to PaperShift in Android battery stats.

### 1.2 Non-Goals (v1.0)
- No accounts, cloud sync, ads, analytics or crash-reporting SDKs.
- No in-app image browser, gallery, favorites, or editing (filters, manual crop).
- No custom rotation intervals (12 hours is fixed by design).
- No live/animated wallpapers.
- No separate home and lock screen images: both screens always show the same image (see §9.2).
- No mixing of Gallery and Bing sources ("Both" — see §9.2).
- No light theme or dynamic color (see §9.2).

---

## 2. Technical Architecture Diagrams

### 2.1 System Architecture & Component Interaction

```
+-----------------------------------------------------------------------------------+
|                                 APPLICATION LAYER                                 |
|                                                                                   |
|  +-------------------------------------+   +-----------------------------------+  |
|  |     Main Rotator UI (Compose)       |   |      Settings Screen (Compose)    |  |
|  |  - Source Picker (Gallery / Bing)   |   |  - Unmetered-only toggle (Bing)   |  |
|  |  - SAF Folder Picker (tree URI)     |   |  - Pause on Battery Saver         |  |
|  |  - Start / Pause, Shift Now         |   |  - Pause on Low Battery           |  |
|  |  - Status (from WorkInfo + outcome) |   |  - Live battery-optimization row  |  |
|  +------------------+------------------+   +-----------------+-----------------+  |
+---------------------|----------------------------------------|--------------------+
                      |      Config + last outcome (DataStore) |
                      v                                        v
+-----------------------------------------------------------------------------------+
|                            BACKGROUND SCHEDULER LAYER                             |
|                                                                                   |
|  WorkManager                                                                      |
|  - Unique periodic work "papershift.rotation" (12h interval, 2h flex),            |
|    enqueued with ExistingPeriodicWorkPolicy.UPDATE on every settings change       |
|  - Unique one-time work "papershift.shift_now" (Start + Shift Now)                |
|  - Constraints (checked by WorkManager BEFORE the worker starts):                 |
|      * Network: NOT_REQUIRED (Gallery) | CONNECTED or UNMETERED (Bing)            |
|      * setRequiresBatteryNotLow(true) if "Pause on Low Battery" is ON             |
|  - Checked INSIDE the worker: PowerManager.isPowerSaveMode()                      |
+--------------------------------------+--------------------------------------------+
                                       | Starts ShiftWorker (CoroutineWorker)
                                       v
+-----------------------------------------------------------------------------------+
|                        PIPELINE: FETCH-PROCESS-DISCARD                            |
|                                                                                   |
|  [Source Evaluator]                                                               |
|     |                                                                             |
|     +--> Gallery: DocumentsContract child query -> pick next (shuffle state)      |
|     |             -> ImageDecoder.createSource(contentResolver, uri)              |
|     |                                                                             |
|     +--> Bing:    OkHttp (no disk cache) -> archive JSON (n=8) -> pick image      |
|                   -> download into ByteArray (Content-Length verified, 25MB cap)  |
|                   -> ImageDecoder.createSource(ByteBuffer)                        |
|                                    |                                              |
|                                    v                                              |
|  [Single-step decode: ImageDecoder]                                               |
|     - EXIF orientation applied automatically                                      |
|     - setTargetSize(): scale so image COVERS target display size                  |
|     - setCrop(): centered rect = exact target size                                |
|     - ALLOCATOR_SOFTWARE; truncated data throws (no OnPartialImageListener)       |
|                                    |                                              |
|                                    v                                              |
|  [WallpaperManager.setBitmap(bitmap, null, true, FLAG_SYSTEM or FLAG_LOCK)]       |
|                                    |                                              |
|                                    v                                              |
|  [Release] drop references to bitmap/buffer, write outcome to DataStore           |
+-----------------------------------------------------------------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------------------+
|                              ANDROID OS SYSTEM LAYER                              |
|   System UI (home screen)  |  Power Manager / Doze / App Standby  |  JobScheduler |
+-----------------------------------------------------------------------------------+
```

### 2.2 Sequence Diagram: Rotation Lifecycle

```
WorkManager            ShiftWorker            Source (SAF / Bing)    WallpaperManager     DataStore
    |                       |                         |                     |                 |
    | constraints met       |                         |                     |                 |
    | (network, battery)    |                         |                     |                 |
    |---- start worker ---->|                         |                     |                 |
    |                       |-- read config ------------------------------------------------>|
    |                       |<---------------------------------------------------------------|
    |                       | isPowerSaveMode &&      |                     |                 |
    |                       | toggle ON? -> SKIPPED --(record outcome)--------------------->|
    |                       |                         |                     |                 |
    |                       |-- list / fetch -------->|                     |                 |
    |                       |<-- uri / bytes ---------|                     |                 |
    |                       | ImageDecoder decode     |                     |                 |
    |                       | (scale + center crop)   |                     |                 |
    |                       |-- setBitmap(SYSTEM|LOCK) -------------------->|                 |
    |                       |<-- wallpaper id --------------------------------|                 |
    |                       |-- record outcome, shuffle / Bing state ------------------------>|
    |<-- Result.success() --|                         |                     |                 |
    | next run per period   |                         |                     |                 |
```

Notes:
- Constraints are evaluated by WorkManager; the worker never starts if they are not met.
- Transient failures return `Result.retry()` while `runAttemptCount < 3`; after that the worker records `FAILED_TRANSIENT` and returns `Result.success()` so the periodic schedule continues unchanged.

### 2.3 State Machine: Rotation Service

```
                    +-------------------+
                    |       IDLE        |<------------------------------------+
                    | (no work enqueued)|                                     |
                    +---------+---------+                                     |
                              | User taps "Start 12-Hour Rotation"            | User taps "Pause"
                              | (enqueue shift_now + periodic)                | (cancel both unique works)
                              v                                               |
                    +-------------------+   constraints unmet   +-------------+---+
          +-------->|     SCHEDULED     |---------------------->|     WAITING     |
          |         | (ENQUEUED, next   |<----------------------| (no network /   |
          |         |  run time known)  |   constraints met     |  low battery)   |
          |         +---------+---------+                       +-----------------+
          |                   | run window reached
          |                   v
          |         +-------------------+
          |         |      RUNNING      |
          |         +--+-------+-----+--+
          |  success   |       |     | battery saver ON & toggle ON
          +------------+       |     +------------------> SKIPPED ---> SCHEDULED
          |                    |
          |  transient error   | blocking error (EC-01, EC-02, EC-03, EC-11, EC-13)
          |  (retry ≤3, then   v
          |   FAILED_TRANSIENT)+-------------------+
          +-------------------| NEEDS_ATTENTION   |
             user fixes cause | (banner + optional|
             (re-enqueue)     |  notification)    |
                              +-------------------+
```

| State | How it is derived | Status copy (Main screen) |
| :--- | :--- | :--- |
| IDLE | No unique work enqueued | "Rotation is off" |
| SCHEDULED | `WorkInfo.State.ENQUEUED`, `nextScheduleTimeMillis` (WorkManager ≥ 2.9) | "Next rotation in about 5h 20m" |
| WAITING | ENQUEUED, but current network/battery does not satisfy constraints (checked by UI) | "Waiting for Wi-Fi to update wallpaper" / "Waiting for battery to recover" |
| RUNNING | `WorkInfo.State.RUNNING` | "Updating wallpaper…" |
| SKIPPED | Last outcome = `SKIPPED_BATTERY_SAVER` | "Skipped: Battery Saver was on. Next try in about 11h" |
| NEEDS_ATTENTION | Last outcome is a blocking error | Error-specific banner (see §7) |

> The countdown is approximate; copy always says "about". WorkManager and Doze may shift runs within the flex window.

---

## 3. Target Users & Problem Statement

### 3.1 Target Audience
- **Minimalist Android users** who want fresh wallpapers without launcher extensions or ad-supported wallpaper hubs.
- **Privacy- and storage-conscious users** who want apps with no image cache and no telemetry.
- **Photography enthusiasts** who enjoy Bing's daily photography or rotating their own photo folders.

### 3.2 User Stories
- As a user, I pick a folder of my photos and tap Start; my wallpaper changes immediately and then twice a day without me opening the app again.
- As a user on a limited data plan, I can make sure Bing images download only on Wi-Fi.
- As a user low on battery, I can be sure PaperShift does nothing while Battery Saver is on.
- As a user, when something breaks (folder deleted, no photos), the app tells me what's wrong and how to fix it in one tap.

### 3.3 Core Problems Solved
1. **App bloat & disk waste:** PaperShift stores zero image files.
2. **Setup friction:** the whole setup is the 4-step flow in §5.1.
3. **Battery drain:** WorkManager with system-managed constraints, a 12-hour cadence, and no foreground service.
4. **Platform neutrality:** works the same on any Android 10+ device, no vendor-specific UI.

---

## 4. Product Architecture & Key Principles

### 4.1 Fetch-Process-Discard Model

**Storage guarantee (precise wording):** PaperShift never writes image bytes to app-private storage, the app's external storage, or shared storage. App data is limited to:
- DataStore preferences (settings, folder URI, shuffle state, last outcome, recent Bing `startdate`s) — target < 16 KB.
- WorkManager's internal SQLite database.
- System-managed files (ART profiles, `code_cache`).

The system stores its own copy of the applied wallpaper (e.g. under `/data/system/users/<id>/`); this is owned by the OS, not PaperShift, and replaces the previous wallpaper rather than accumulating.

**Implementation rules:**
- OkHttp client is built **without** a `Cache`; `HttpResponseCache` is never installed.
- Bing image bytes are held in a single `ByteArray` (capped at 25 MB) and released after decode.
- No temp files, no `cacheDir` / `externalCacheDir` usage anywhere in the image path.
- Bitmap and buffers are dereferenced right after `setBitmap()`. On API 26+ bitmap pixels live in the native heap and are reclaimed by GC; calling `recycle()` is optional and may be kept for clarity.

### 4.2 Image Pipeline

1. **Target size:** natural-portrait size of the **largest** display, from `WindowMetricsCalculator.computeMaximumWindowMetrics()` (Jetpack WindowManager, usable from a non-visual context in a Worker). If the device is in landscape when the worker runs, width/height are swapped back to portrait.
2. **Decode:** `ImageDecoder` (API 28+, always available at minSdk 29):
   - `setTargetSize()` so the scaled image fully covers the target (cover scaling, aspect preserved).
   - `setCrop()` to the centered rectangle of exactly the target size.
   - `allocator = ALLOCATOR_SOFTWARE` (required for `WallpaperManager` to compress the bitmap).
   - EXIF orientation is applied automatically.
   - No `OnPartialImageListener` is set, so truncated input throws `DecodeException` instead of producing a half-grey image.
3. **Minimum resolution:** images whose shorter side is < 720 px are skipped (treated like EC-03).
4. **Apply:** `WallpaperManager.setBitmap(bitmap, null, /*allowBackup*/ true, FLAG_SYSTEM or FLAG_LOCK)` — one call, so home and lock screen always show the same image. (Setting `FLAG_SYSTEM` alone was rejected: Android would copy the old wallpaper to the lock screen and the lock screen would stop changing.)
5. **Memory budget:** peak app memory increase (Java + native) during a shift ≤ 40 MB on a 1440×3200 device. The output bitmap alone is ~18.4 MB (ARGB_8888); single-step decode avoids holding a second full-size bitmap.

**Supported formats:** JPEG, PNG, WebP, HEIC/HEIF (where the device has a HEIF decoder), AVIF (Android 12+). Unsupported or undecodable files are skipped (EC-03).

### 4.3 Scheduling (WorkManager)

| Item | Spec |
| :--- | :--- |
| Periodic work | `PeriodicWorkRequestBuilder<ShiftWorker>(12, HOURS, 2, HOURS)` with `setInitialDelay(12, HOURS)`, unique name `papershift.rotation` |
| Enqueue policy | `ExistingPeriodicWorkPolicy.UPDATE` — re-enqueued whenever source, network or battery settings change, so new constraints take effect without resetting the cadence |
| Immediate shift | On Start and on **Shift Now**: `OneTimeWorkRequest` (non-expedited), unique name `papershift.shift_now`, `ExistingWorkPolicy.KEEP` (ignores double taps) |
| Network constraint | Gallery: `NOT_REQUIRED`. Bing: `CONNECTED`, or `UNMETERED` when "Unmetered networks only" is ON |
| Battery constraint | `setRequiresBatteryNotLow(true)` when "Pause on Low Battery" is ON |
| Backoff | `BackoffPolicy.EXPONENTIAL`, 15 min initial |
| Retry cap | Worker returns `Result.retry()` only while `runAttemptCount < 3`; then records `FAILED_TRANSIENT` and returns `Result.success()` |
| Pause | `cancelUniqueWork()` for both unique names |
| Reboot | Handled by WorkManager's own reschedule receiver. PaperShift declares **no** custom `BOOT_COMPLETED` receiver |

### 4.4 Battery Behaviour

| Setting | Mechanism | Behaviour |
| :--- | :--- | :--- |
| **Pause on Battery Saver** (default ON) | Worker checks `PowerManager.isPowerSaveMode()` at start (WorkManager has no Battery Saver constraint) | Skip this cycle, record `SKIPPED_BATTERY_SAVER`, return `Result.success()`. No catch-up job is scheduled (avoids extra wake-ups); the next attempt is the next periodic run. |
| **Pause on Low Battery** (default ON) | `setRequiresBatteryNotLow(true)` | WorkManager defers the run until the system leaves its low-battery state, then runs it. The threshold is **system-defined** (commonly 15%, varies by device) and the constraint counts as satisfied while charging. UI copy says "low battery", never a fixed percentage. |
| **Manual Shift Now** | — | User-initiated, so it ignores both battery pauses but still respects "Unmetered networks only". |

PaperShift does **not** request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The Settings screen shows the live status and links to system settings (§5.2).

---

## 5. Feature Specifications & Screens

### 5.1 Screen 1: Rotator (Main Screen)

**Setup flow (the "4 steps"):**
1. Choose a source: **My Gallery** or **Bing Daily**.
2. (Gallery only) Choose a folder via the SAF folder picker.
3. Tap **Start 12-Hour Rotation**. Helper text under the button: *"Changes your home and lock screen."* If a live wallpaper is active, confirm replacing it (EC-09). On Android 13+, the app then asks for notification permission (optional; used only for NEEDS_ATTENTION alerts).
4. The first wallpaper applies immediately; the status shows the next rotation time.

**Components:**
1. **Source segmented control:** My Gallery / Bing Daily. Changing it re-enqueues work (UPDATE) and does not trigger an immediate shift.
2. **Folder row** (visible only for My Gallery): shows the folder's display name (e.g. `📁 Wallpapers/Nature`) and the number of usable images; tap to change. Picking a new folder calls `takePersistableUriPermission` on the new URI and `releasePersistableUriPermission` on the old one.
3. **Primary button:** "Start 12-Hour Rotation" / "Pause Rotation".
4. **Shift Now** (secondary button, enabled while rotation is on): applies the next image immediately.
5. **Status line / banner:** per the table in §2.3.
6. **Image credit** (Bing only): the current image's `copyright` string (e.g. *"Two wild tom turkeys (© davidsdodd/Getty Images)"*), tappable to open its `copyrightlink`.
7. **Top bar:** "PaperShift" title and a Settings icon (with content description "Settings").

### 5.2 Screen 2: Settings

1. **Top bar:** back button and "Settings" title.
2. **System & Battery**
   - **Background activity:** live value from `PowerManager.isIgnoringBatteryOptimizations()` → "Battery usage: Optimized" or "Battery usage: Unrestricted".
   - **Link:** "Manage in system settings →" opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for PaperShift (works on all OEMs; the battery section is reachable from there).
   - **Helper text:** "Some phones limit background apps. If rotations are late, allow PaperShift to run in the background."
   - **Toggle: Pause on Battery Saver** (default ON).
   - **Toggle: Pause on low battery** (default ON). Subtitle: "Uses your phone's low-battery level".
3. **Network & Data** (shown for Bing; disabled with explanation for Gallery)
   - **Toggle: Unmetered networks only** (default ON). Subtitle: "Usually Wi-Fi. Bing images never use mobile data."
   - **Storage statement:** "PaperShift never saves downloaded or processed images to your device."
4. **Display**
   - Static note: "Images are automatically center-cropped to fill your screen."
5. **About**
   - App version, open-source licenses, Bing image attribution (title/copyright string from the Bing response for the current image), privacy statement (§10).

### 5.3 Source Rules

**My Gallery**
- Only the top level of the chosen folder is scanned in v1.0 (subfolders ignored; recursive scan is v1.1).
- Listing uses `DocumentsContract.buildChildDocumentsUriUsingTree` with a projection of `DOCUMENT_ID`, `MIME_TYPE`, `LAST_MODIFIED` (not `DocumentFile.listFiles()`, which issues one IPC per property). Target: ≤ 2 s for 5,000 files.
- Files are filtered by MIME type (`image/jpeg`, `image/png`, `image/webp`, `image/heic`, `image/heif`, `image/avif`).
- **Non-repeating shuffle:** DataStore keeps `seed`, `position`, and a folder fingerprint (`count` + max `LAST_MODIFIED`). If the fingerprint changes, a new shuffle starts, and the currently applied image is excluded from the first pick. No image repeats until all usable images have been shown.

**Bing Daily**
- Endpoint: `https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=<market>`. Market comes from the device locale; unsupported locales fall back to `en-US`.
- Image URL: `https://www.bing.com{urlbase}_UHD.jpg`, falling back to `_1920x1080.jpg`. Verified 2026-11-26: `_UHD.jpg` (≈ 3.7 MB), `_1920x1080.jpg` (≈ 0.3 MB) and a portrait `_1080x1920.jpg` (≈ 0.3 MB) all return `image/jpeg`.
- Fields used: `startdate`, `urlbase`, `copyright`, `copyrightlink`. The `copyright` string is stored with the applied `startdate` so the credit can be shown (§5.1).
- **Cadence rule (Bing publishes 1 image/day, PaperShift rotates 2×/day):**
  1. If today's image (`idx=0`) has not been applied yet, apply it.
  2. Otherwise apply a random image from the 8-day archive, excluding the currently applied one.
  3. DataStore keeps the last 8 applied `startdate` values.
- Known limitation: Bing images are landscape (16:9); the portrait center crop uses roughly the middle third and is upscaled ~1.5× on 1440p phones.
- Timeouts: connect 15 s, read 30 s.

---

## 6. Comprehensive Test Plan

### 6.1 Testing Levels & Matrix

| Test Level | Scope | Tools & Frameworks | Target Coverage |
| :--- | :--- | :--- | :--- |
| **Unit** | Preference mappers, crop/scale math, shuffle logic, Bing JSON parser, Bing cadence rule | JUnit 4, MockK, Truth, Robolectric (JUnit 4 runner) | ≥ 90% line coverage of domain logic |
| **Worker** | Constraints, retry cap, outcomes, UPDATE re-enqueue | `androidx.work:work-testing` (`WorkManagerTestInitHelper`, `TestListenableWorkerBuilder`) | 100% of state transitions in §2.3 |
| **Instrumentation** | Compose UI states, SAF permission persistence, WallpaperManager wrapper | Compose UI Test, AndroidX Test Runner, Espresso | All 4 setup steps + every status state |
| **System & Battery** | Doze, standby buckets, battery saver, low battery | adb (commands below), Android Studio Energy/Power profiler, Battery Historian | No app-held wake locks outside WorkManager |
| **E2E** | Full fetch → decode → set → release | Physical device matrix (below) | Wallpaper change verified via `WallpaperManager.getWallpaperId()` incrementing for both `FLAG_SYSTEM` and `FLAG_LOCK` |

**Device matrix:** Android 10, 11, 12, 13, 14, 15, 16, 17 (API 29–37) on a mix of Pixel, Samsung, Xiaomi, OnePlus/Oppo and Motorola, including at least one foldable, one tablet, and one 720p device.

**Forcing a run during tests:** `adb shell cmd jobscheduler run -f <package> <jobId>` (job id from `adb shell dumpsys jobscheduler | grep <package>`).

### 6.2 Functional Test Suites

#### Suite 1: SAF & Local Folder
- **TC-SAF-01 (Permission persistence):** Grant survives reboot and app update. After changing folders, the old grant is released (`getPersistedUriPermissions()` contains exactly one entry).
- **TC-SAF-02 (Non-repeating shuffle):** With N images, N consecutive shifts show N distinct images; the first shift of the next cycle differs from the last of the previous cycle.
- **TC-SAF-03 (Folder changes between runs):** Adding/removing files triggers a reshuffle without repeats or crashes.
- **TC-SAF-04 (Subfolders ignored):** Images in subfolders are not used; no `SecurityException`.
- **TC-SAF-05 (File type filtering):** `.txt`, `.pdf`, `.mp4` are skipped silently.
- **TC-SAF-06 (Large folder):** 5,000-file folder lists in ≤ 2 s on a mid-range reference device.

#### Suite 2: Bing
- **TC-BING-01 (Contract & parsing):** Parses archive JSON; builds UHD and 1920×1080 URLs; both resolve (HTTP 200, `image/jpeg`). Unknown fields are ignored; missing required fields → EC-05.
- **TC-BING-02 (Zero image storage audit):** On a debuggable build, run `adb shell run-as <package> find . -type f -exec ls -l {} \;` before and after 5 Bing shifts. Pass if: no new files outside the allowlist (`files/datastore/*`, `no_backup/androidx.work.workdb*`, `code_cache/*`, profile files); no file starts with JPEG/PNG/WebP magic bytes; `/sdcard/Android/data/<package>` does not exist or is empty.
- **TC-BING-03 (Unmetered only):** With the toggle ON and only mobile data available, the worker does **not start** (WorkInfo stays ENQUEUED, UI shows WAITING) and no request reaches bing.com (verified with a proxy). Turning Wi-Fi on starts the run.
- **TC-BING-04 (Cadence rule):** Two runs on the same day apply today's image and then a different archive image; no consecutive duplicates.
- **TC-BING-05 (Market fallback):** Unsupported locale falls back to `en-US`.
- **TC-BING-06 (Credit):** After a Bing shift, the Main screen shows that image's `copyright` string; tapping it opens `copyrightlink`.

#### Suite 3: Scheduling
- **TC-WORK-01 (Cadence):** On reference devices without OEM restrictions, ≥ 95% of runs occur ≤ 14 h after the previous one over a 7-day soak.
- **TC-WORK-02 (Start / Pause):** Start enqueues `papershift.shift_now` and `papershift.rotation`; Pause cancels both.
- **TC-WORK-03 (Reboot):** After a reboot, `papershift.rotation` is still ENQUEUED with the same period (no custom receiver involved).
- **TC-WORK-04 (Settings change):** Toggling "Unmetered networks only" or switching source updates the periodic work's constraints (verified via `WorkInfo.constraints`) without changing the next run time by more than the flex window.
- **TC-WORK-05 (Retry cap):** With a 503 response, the worker retries at most 3 times, then records `FAILED_TRANSIENT`; the periodic work stays ENQUEUED.
- **TC-WORK-06 (First run & Shift Now):** Start applies a wallpaper within 60 s with the app in the foreground; double-tapping Shift Now applies one image, not two.

#### Suite 4: Battery & Power
- **TC-BAT-01 (Battery Saver):** `adb shell dumpsys battery unplug` then `adb shell settings put global low_power 1`; force a run → outcome `SKIPPED_BATTERY_SAVER`, wallpaper unchanged. Shift Now still works.
- **TC-BAT-02 (Low battery):** `adb shell dumpsys battery unplug` and `adb shell dumpsys battery set level <device low level − 1>` → run does not start. `dumpsys battery set level <low level + 5>` → run starts. Clean up with `adb shell dumpsys battery reset`.
- **TC-BAT-03 (Doze):** `adb shell dumpsys deviceidle force-idle`; run happens only in a maintenance window; Battery Historian shows no wake locks held by PaperShift outside WorkManager's job wake lock.

#### Suite 5: Image Pipeline & Memory
- **TC-MEM-01 (No leak):** 20 sequential shifts in the memory profiler; Java and native heap return to baseline (± 5 MB) after each.
- **TC-MEM-02 (Aspect fit):** Portrait, landscape and square sources are center-cropped without distortion on 16:9, 19.5:9, 20:9, 21:9 phones, 4:3 and 16:10 tablets, and a foldable inner display.
- **TC-MEM-03 (108 MP source):** Decodes without OOM within the 40 MB budget.
- **TC-IMG-01 (EXIF):** JPEGs with EXIF orientation 3, 6 and 8 display upright.
- **TC-IMG-02 (HEIF/AVIF):** Decodes where supported; skipped without error where not.
- **TC-IMG-03 (Truncated download):** Cutting the connection at 50% → no `setBitmap` call; retry follows TC-WORK-05.

#### Suite 6: UX, Accessibility & Policy
- **TC-UX-01 (Live wallpaper):** With a live wallpaper active, Start shows the confirmation; Cancel leaves everything unchanged.
- **TC-UX-02 (Device policy):** On a work profile/device where `isSetWallpaperAllowed()` is false, Start is disabled with an explanation.
- **TC-A11Y-01:** TalkBack reads every control and state; all touch targets ≥ 48 dp; layout works at 200% font scale; text contrast ≥ 4.5:1.
- **TC-UX-03 (Notification denied):** With POST_NOTIFICATIONS denied, NEEDS_ATTENTION appears in-app only; no crash.
- **TC-UX-04 (Home and lock):** After a shift, home and lock screen show the same image, including on a device where the user had previously set a different lock screen wallpaper.
- **TC-UX-05 (Dark only):** With the system in light mode, the app still renders the dark theme correctly (no light-on-light text, dark status/navigation bars).

---

## 7. Edge Cases & Exception Handling

| ID | Scenario | Failure Mode | Expected Behaviour | User-Facing Feedback |
| :--- | :--- | :--- | :--- | :--- |
| **EC-01** | Selected folder deleted or moved | `FileNotFoundException` / `SecurityException` on query | Catch in worker; record outcome; cancel both works → NEEDS_ATTENTION | Banner: *"Your folder is missing. Tap to choose a new one."* |
| **EC-02** | Folder has zero usable images | Empty list | Don't change the wallpaper; keep the schedule (user may add photos without opening the app) → NEEDS_ATTENTION | *"This folder has no photos PaperShift can use. Add images or pick another folder."* |
| **EC-03** | Corrupt, unsupported or too-small image | `DecodeException` / `IOException` / below 720 px | Skip to the next image in the shuffle, up to 3 attempts per run. If all attempts fail, keep the schedule → NEEDS_ATTENTION | Silent unless all fail: *"PaperShift couldn't read the images in this folder."* |
| **EC-04** | No internet during a Bing shift | Worker not started (constraint) or `UnknownHostException` / `SocketTimeoutException` mid-run | Constraint keeps the run waiting; mid-run errors → retry per §4.3. Current wallpaper unchanged | WAITING: *"Waiting for a connection to update your wallpaper."* |
| **EC-05** | Bing 5xx or changed JSON format | `HttpException` / parse error | Retry per §4.3, then `FAILED_TRANSIENT` | None (status keeps showing the next run time) |
| **EC-06** | Connection drop mid-download | Truncated bytes | Downloaded length must equal `Content-Length`; `ImageDecoder` throws on incomplete data. Discard buffer, retry per §4.3 | None — no half-rendered wallpaper |
| **EC-07** | Very large photo (e.g. 108 MP) | OOM | `ImageDecoder.setTargetSize()` decodes directly at target size | None; memory stays within budget |
| **EC-08** | Foldable / flip device | Display size differs between folded and unfolded | Crop to the **largest** display's portrait size; the system crops further for the smaller display | Wallpaper fills both screens; outer screen shows a tighter center crop |
| **EC-09** | Live wallpaper active | `setBitmap` replaces it | On Start, check `WallpaperManager.getWallpaperInfo() != null`; ask for confirmation. Background runs proceed once confirmed | Dialog: *"A live wallpaper is active. PaperShift will replace it with photos."* |
| **EC-10** | OEM background restrictions | Runs delayed beyond 12 h | Documented in Settings with a link to app details (§5.2) | *"Manage in system settings →"* plus helper text |
| **EC-11** | Folder access lost (SD card removed, app data cleared, permission released) | `SecurityException` | Same as EC-01 | *"PaperShift lost access to your folder. Tap to choose it again."* + notification if permitted |
| **EC-12** | Extreme/ultra power-saving mode | Background work blocked by the OS | No special handling; WorkManager runs once the mode ends | None |
| **EC-13** | Wallpaper changes blocked by device policy | `isSetWallpaperAllowed()` = false or `isWallpaperSupported()` = false | Disable Start; if detected in the worker, cancel works → NEEDS_ATTENTION | *"Your device doesn't allow changing the wallpaper."* |
| **EC-14** | Settings changed while a run is in progress | Run uses old settings | The running job finishes with the old settings; changes apply from the next run | None |
| **EC-15** | User changes wallpaper manually in system settings | PaperShift overwrites it on the next run | Expected behaviour while rotation is on | None (documented in onboarding copy) |
| **EC-16** | User had a separate lock screen wallpaper | It is replaced by the shared image | Expected in v1.0 (§4.2 step 4); separate images are v1.1 | Helper text on Start: *"Changes your home and lock screen."* |
| **EC-17** | Device's own lock screen feature (rotating lock screen stories, etc.) is active | It may override the lock wallpaper | Out of scope; PaperShift does not detect or disable it | None |

---

## 8. Visual Design & Theme System

- **Theme:** Material 3 in Compose. **Dark theme only in v1.0**, regardless of the system light/dark setting; status and navigation bars use dark styling. No dynamic color.
- **Dark palette:** surface `#14121A`; accent (toggles, selected segment, primary button container) `#7C4DFF` with white on-accent text (4.8:1). For **accent-coloured text on the dark surface**, use the lighter tint `#B39DFF` (≈ 8:1); `#7C4DFF` on `#14121A` is only ≈ 3.8:1 and fails AA for body text.
- **Light palette:** not in v1.0 (v1.1, §9.2).
- **Cards:** one level of rounded containers, `RoundedCornerShape(12.dp)`, 1 dp border using `MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)`. No blur or elevation shadows.
- **Typography:** Plus Jakarta Sans (bundled), falling back to the system sans-serif. Supports font scaling up to 200%.
- **Tone & copy:** functional and platform-neutral — no vendor names or vendor feature names.

---

## 9. Release Milestones & Acceptance Criteria

### 9.1 Acceptance Criteria for MVP (v1.0)
1. **SAF folder selection:** Chooses and persists read access to a folder with JPEG/PNG/WebP/HEIF files; access survives reboots and app updates (TC-SAF-01).
2. **Bing ingestion:** Fetches and applies Bing images with no image bytes written to storage (TC-BING-02) and no consecutive duplicates (TC-BING-04).
3. **Cadence:** ≥ 95% of runs within 14 h of the previous run on reference devices over a 7-day soak (TC-WORK-01).
4. **Battery & network compliance:** TC-BAT-01, TC-BAT-02 and TC-BING-03 pass on every device in the matrix.
5. **Memory:** Zero OOM across 50 consecutive shifts; peak memory increase ≤ 40 MB on a 1440×3200 device (TC-MEM-01, TC-MEM-03).
6. **First run:** Wallpaper changes within 60 s of tapping Start (TC-WORK-06).
7. **Accessibility:** TC-A11Y-01 passes.
8. **Bing credit:** Every applied Bing image's credit is shown in-app (TC-BING-06), and the Bing source can be disabled by an app update without affecting My Gallery (§11, R-1).
9. **Home and lock:** Both screens change together on every shift (TC-UX-04).

### 9.2 v1.1 Roadmap
- Target picker: Home / Lock / Both, including separate images per screen.
- "Both" source mode (alternate Gallery and Bing).
- Light theme following the system setting; optional dynamic color (Android 12+).
- Quick Settings tile ("Shift Now").
- Recursive subfolder scan; multiple folders.
- Per-display crops on foldables where the platform supports them.

---

## 10. Permissions, Privacy & Platform Compliance

| Permission | Type | Why |
| :--- | :--- | :--- |
| `SET_WALLPAPER` | Normal | Apply the wallpaper |
| `INTERNET` | Normal | Bing source |
| `ACCESS_NETWORK_STATE` | Normal | Network constraints (merged by WorkManager) |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | Normal | Merged by WorkManager; PaperShift has no receiver of its own |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) | Optional; NEEDS_ATTENTION alerts only |

- **No storage permissions:** folder access uses SAF only.
- **No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`:** avoids Play policy review; users go to system settings themselves.
- **Backup:** `android:allowBackup="false"` in v1.0. Persisted SAF grants are not restored by backup, so restoring settings would only produce a broken folder state.
- **Logging:** Release builds keep only the last outcome code in DataStore. Logcat output exists only in debug builds. No crash-reporting or analytics SDK.
- **Network privacy:** In Bing mode, requests go to `www.bing.com` and include the device's IP address and the market code. The privacy statement and the Play Data Safety form must state this. No other network calls.

---

## 11. Risks

| ID | Risk | Impact | Mitigation |
| :--- | :--- | :--- | :--- |
| R-1 | **Accepted risk.** The Bing endpoint is public and needs no key, but it has no published terms for third-party apps, and the images are copyrighted by their photographers/agencies (e.g. *"© davidsdodd/Getty Images"* in the 2026-11-26 response). PaperShift's own code may be open source; the images are not | Takedown request or store removal of the Bing feature | Show each image's credit and link (§5.1); never store or redistribute images; keep Bing isolated so it can be removed in an update; respond promptly to any rights-holder request |
| R-2 | The Bing endpoint changes or disappears | Bing mode stops working | Tolerant parser, retry cap, clear NEEDS_ATTENTION copy after repeated failures; remote kill not available (no backend), so ship an app update |
| R-3 | OEM background restrictions delay or block work | Missed rotations, bad reviews | Settings helper text and link; OEM devices in the test matrix |
| R-4 | Upscaled Bing images look soft on high-res phones | Perceived low quality | Use the UHD variant; the portrait `_1080x1920.jpg` variant exists but has lower resolution, so compare both on a 1440p device before release |

---

## 12. Success Metrics

Because PaperShift has no telemetry, metrics come from Google Play Console:
- Android vitals: user-perceived crash rate < 0.5%, ANR rate < 0.2%, no "excessive wakeups" or "stuck partial wake locks" flags.
- Store rating ≥ 4.3 after 90 days.
- 30-day uninstall rate below the category median.
- Download size < 8 MB.

---

## 13. Decisions Log

Decided 2026-11-26.

| # | Topic | Decision | Where it applies |
| :--- | :--- | :--- | :--- |
| 1 | Bing in v1.0 | Ship Bing Daily in v1.0 using the public `HPImageArchive.aspx` endpoint, with no separate legal review. Recorded as an **accepted risk**: the endpoint is public, but the images stay copyrighted by their owners. Mitigated with per-image credit and an isolated, removable Bing module | §5.1, §5.3, §9.1 #8, §11 R-1 |
| 2 | "Both" source | Deferred to v1.1 | §1.2, §9.2 |
| 3 | Shift Now | In-app button in v1.0; Quick Settings tile in v1.1 | §4.3, §5.1, TC-WORK-06 |
| 4 | Target screens | **Home and lock screen together**, always the same image (`FLAG_SYSTEM or FLAG_LOCK`). Separate images / target picker in v1.1 | §4.2, §5.1, EC-16, EC-17, TC-UX-04, §9.1 #9 |
| 5 | Battery Saver | Skip the cycle; no catch-up run; Shift Now still works | §4.4, TC-BAT-01 |
| 6 | Backup | `android:allowBackup="false"` | §10 |
| 7 | Crash reporting | None; Play Console Android vitals only | §10, §12 |
| 8 | Theme | Dark only in v1.0; light theme and dynamic color in v1.1 | §1.2, §8, TC-UX-05, §9.2 |
| 9 | Minimum resolution | Skip images whose shorter side is < 720 px | §4.2, EC-03 |
