# UniFX

A small, modular file manager for Android, with a built-in code editor, read-only text viewer and hex editor.

UniFX has **zero library dependencies**. It uses only the Android framework and the Kotlin standard library: no AndroidX, no support libraries, no image assets. All screens are built in code and all icons are vector drawables, so the release APK is measured in kilobytes.

- Package: `x.vladgba.fileman`
- Android 5.0+ (API 21), targets API 35
- Licence: MIT

## Features

### File browser
- List and grid views, switchable from the menu
- Clickable breadcrumb path bar; Back goes up one folder until you reach a storage root
- Sort by name, size, date or type, ascending or descending; folders always come first
- Show/hide hidden files, and filter the current folder by name
- Search the current folder and everything inside it (search row → Subfolders…) by name (part of it, `*.txt` wildcard, or regex) and/or by text inside files (plain or regex, with line number and snippet). Results stream in and the search can be stopped; tap a result to open it, long-press to show it in its folder
- Storage shortcuts: internal storage, SD cards / USB drives, the app's own files, and `/`
- Long-press to multi-select: copy, cut, paste, delete, rename, share, open with…, properties
- Rename by pattern (⋮ → Rename by pattern…): wildcard (`*.jpeg` → `*.jpg`, `IMG_*` → `Photo *`) or Java regex with `$1` groups, optional match case, and a `[N]` / `[NNN]` counter. A live preview marks invalid names and clashes, which are skipped; renames go through temporary names, so swaps work
- Archives: browse and preview `.zip`, `.jar`, `.aar`, `.apk`, `.epub`, `.tar`, `.tgz`, `.tar.gz`, and `.gz` archives without extracting (tap to view, or ⋮ → Archive preview). For APK files, displays app icon, label, package id, version, and an Install button. Compress selection to `.zip` (⋮ → Compress to ZIP), and extract archives into a folder next to them (⋮ → Extract here). Entries resolving outside ("zip/tar slip") are refused; non-UTF-8 zip names are retried as CP437 on Android 7+
- Copy and move run in the background. Name clashes become `name (1).ext`, and delete never follows symlinks
- Files are shared with other apps through a small built-in content provider (no AndroidX `FileProvider`)

### Text editor
- Syntax highlighting for Kotlin, Java, JavaScript/TypeScript, C/C++, C#, Go, Rust, Swift, Dart and more; Python and shell scripts; XML/HTML/SVG; JSON; CSS; SQL; Markdown; YAML/TOML/INI/properties
- **Asynchronous, line-by-line highlighting**: tokenizing runs on a background thread and only the visible lines (plus a margin) are coloured. The state at the start of each line is remembered, so multi-line comments and strings are coloured correctly and an edit only re-scans the lines after it
- Line-number gutter, word wrap, text size, go to line
- Find / replace / replace all, and undo / redo
- Show unprintable characters: `·` space, `→` tab, `¶` line end, `␀`…`␟` control characters
- Kinetic scrolling (fling), which `EditText` doesn't provide itself
- Keeps the file's encoding (UTF-8, with or without BOM; falls back to Latin-1 so any byte round-trips), line endings (LF/CRLF) and writes atomically via a temp file + rename
- Picks a truly fixed-width system font at runtime, because some vendors' "monospace" isn't

### Read-only text viewer
Used automatically for files over 1 MB, binary files and files you can't write to.
- Never loads the whole file: rows are read from disk on demand through a small page cache
- A sparse line index is built in the background (every 32nd row is stored); the file is scrollable while indexing
- Long lines and binary data are split into short rows (≤ 1024 bytes, never inside a UTF-8 sequence)
- Background per-row highlighting, find (ASCII case-insensitive, wraps around), go to line, copy a row with a long-press

### Hex editor
- Paged access: works on files of any size; saving writes back only the changed bytes
- Offset / hex / ASCII columns, fitting 16, 12, 8 or 4 bytes per row to the screen width
- Overwrite editing with an on-screen 0–F keypad; edited bytes are highlighted; undo
- Go to offset (hex or decimal), find hex bytes or text, find next, write text at the cursor
- Status bar with the value under the cursor as u8/i8, binary, u16/i16 and u32/i32 (little-endian)

### Opening files from other apps
UniFX appears in other apps' *Open with* and *Share* lists as **UniFX Editor** (text-like files) and **UniFX Hex** (any file). It handles `ACTION_VIEW`, `ACTION_EDIT` and `ACTION_SEND`.
- If the link resolves to a real file (a `file://` path, the system storage and Downloads providers, or a provider that exposes the file path), that file is opened directly
- Otherwise the content is copied into the app cache and the copy is opened. If the sending app granted write access, every save is also written back to the original; otherwise the copy is read-only and opens in the viewer
- Shared plain text (no file attached) opens as a read-only text copy
- Paths inside UniFX's own private data folder are never opened this way
- Copies older than a day are cleaned up automatically

## Building

Requirements: JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/
./gradlew testDebugUnitTest # JVM unit tests (JUnit is test-only; the APK has no dependencies)
```

Or open the project in Android Studio / IntelliJ IDEA and run it.

Release builds use R8 with resource shrinking, and the Kotlin compiler's runtime null-check assertions are turned off to keep the dex small. **The release build is signed with the debug key** so it installs straight away. Configure your own `signingConfig` in `app/build.gradle.kts` before publishing.

## Permissions

| Android version | What UniFX asks for |
| --- | --- |
| 11+ (API 30+) | "All files access" (`MANAGE_EXTERNAL_STORAGE`), granted from a banner that opens the system settings page |
| 6–10 (API 23–29) | `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` at runtime |
| 5.x (API 21–22) | Granted at install |

Publishing to Google Play with `MANAGE_EXTERNAL_STORAGE` requires a permission declaration for review.

## Project layout

```
app/src/main/java/x/vladgba/fileman/
├── MainActivity.kt      file browser: list/grid, breadcrumbs, sorting, selection, file operations
├── FileAdapter.kt       list rows and grid tiles, drawn type badges
├── Entry.kt             file snapshot + type → colour mapping
├── FileOps.kt           copy / move / delete / unique names / atomic write (background thread)
├── Storage.kt           storage roots and permission handling
├── FilesProvider.kt     minimal content:// provider for sharing and "open with"
├── Openers.kt           module registry (see below), write-back for files opened from other apps
├── OpenActivity.kt      entry point for VIEW / EDIT / SEND intents from other apps
├── EditorActivity.kt    text editor screen, undo/redo history
├── CodeEditText.kt      EditText with gutter, unprintable markers and kinetic scrolling
├── Highlighter.kt       async line-by-line highlighter for the editor
├── Syntax.kt            languages, line tokenizer, colour palettes
├── ViewerActivity.kt    read-only paged text viewer (ByteSource, LineIndex)
├── HexActivity.kt       hex editor (HexDoc paged document)
├── ZipActivity.kt       archive previewer (ZipTree, virtual folders, APK banner)
├── ZipOps.kt            zip compress / extract / tree inspection
├── TarOps.kt            tar / tgz / gz archive parser and extraction
├── ArchiveOps.kt        unified archive gateway (.zip, .apk, .tar, .tgz, .gz)
├── Fonts.kt             runtime selection of a truly monospace font
└── Ui.kt                small shared UI helpers (dp, insets, dialogs)
```

## Modules

Built-in viewers are modules registered in `Openers.modules`. When you tap a file, the first module whose `canOpen()` returns true handles it. Anything unclaimed goes to an external app via `ACTION_VIEW`, and "Open with…" lists every module plus other installed apps.

```kotlin
interface FileOpener {
    val title: Int                       // name shown in "Open with…"
    fun canOpen(f: File): Boolean        // claim the file on a plain tap
    fun open(a: Activity, f: File)
}
```

Current modules: `TextModule` (text editor; large or read-only files are routed to the viewer), `ViewerModule` (read-only text viewer), `HexModule` (hex editor) and `ZipModule` (archive previewer). To add one, implement `FileOpener`, add its activity to the manifest and append it to `Openers.modules`.

## Licence

MIT. See [LICENSE](LICENSE).
