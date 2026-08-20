# requestr v4.0 — Real Playback + ProGuard Stability

## Critical Fixes

### 1. ProGuard/R8 Stability Fix (APK 10MB → 15MB)
The release APK shrunk to ~10MB because R8's aggressive `-dontoptimize` was stripping code needed
by Compose reflection, BouncyCastle JCE service-loaders, Room annotation processing, and Moshi
code-gen. This caused the interceptor, CA generation, and other subsystems to fail silently.

**Fix:** Added `-dontoptimize` to disable R8's whole-program inlining and class merging, plus
comprehensive `-keep` rules for every library and custom package. The APK is now 15MB — stable
and complete.

### 2. Real MediaPlayer Audio Playback
`MusicRepository.togglePlayPause()` was a pure boolean toggle with no actual audio. Tapping play
did nothing.

**Fix:** Added `android.media.MediaPlayer` integration with:
- Async prepare from file path (populated via MediaStore DATA column)
- Real play/pause/stop/seek controls
- Auto-advance to next track on completion
- Position tracking (250ms updates to StateFlow)
- Error resilience with player recreation on failure

### 3. GlassCard Border Fix
`GlassCard` used `clip(shape).background(color)` instead of `background(color, shape)`, causing
borders to render outside the clip area — visible as overlapping outlines in Music playlists
and other screens.

**Fix:** Changed to `background(color, shape)` + `border(1.dp, borderColor, shape)` + `clip(shape)`.

## Completed TODOs

| # | Item | What changed |
|---|------|-------------|
| 37 | Custom Tools builder | ToolsScreen "Create Custom Tool" sheet writes to ToolsRepository. Custom tools now appear in catalog. |
| 43 | Keyboard shortcuts | Ctrl+Enter (send request), Ctrl+Shift+N (new request), Escape (back) in MainActivity.onKeyListener. |
| 44 | Chats section | ChatScreen + ChatRepository (OpenAI-compatible + local fallback). Wired in NavHost, nav drawer, HomeScreen section nav, and deep links. |

## Files Changed
- `app/build.gradle.kts` — version 3.9→4.0 (code 14), ProGuard config
- `app/proguard-rules.pro` — `-dontoptimize` + comprehensive keep rules for all packages
- `AppRepositories.kt` — MusicRepository: MediaPlayer playback, position tracking, player lifecycle
- `AppModels.kt` — MusicTrackItem: added `filePath` field
- `LocalMusicScanner.kt` — Added MediaStore.DATA column to projection
- `ToolsRepository.kt` — Custom tools included in `getToolsCatalog()`
- `ToolsScreen.kt` — Removed `remember` cache so custom tools appear immediately
- `CommonComponents.kt` — GlassCard: fixed modifier order
- `AppNavigationDrawer.kt` — Added "AI Chat Assistant" nav item
- `MainActivity.kt` — Chat in rootRoutes, section nav, keyboard shortcuts
- `TODO.md` — Updated progress tracker

## APK Artifacts
- **Debug:** 28MB (`app-debug.apk`)
- **Release:** 15MB (`app-release.apk`) — signed, minified (no-optimize)

## Remaining (future subsystems)
- #47 Plugin architecture — plugin lifecycle + permissions + custom decoders
- #48 Local API — REST endpoints for captures/flows/tools/rules/sessions
- #49 CLI — command-line interface as separate Gradle module
