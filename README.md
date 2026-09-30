# Vellum — a PDF viewer & editor for Android

*Paper, ink and an editor's red pen.* Vellum is a native Android app (Kotlin + Jetpack Compose) with its own look: warm paper tones, a vermilion accent, serif display type, a floating ink-blue **tool dock**, a draggable **page spine** on the edge, "slip of paper" notifications and index-card recent files.

## Features

**Reading**
- Smooth continuous scrolling, pinch-zoom (up to 500%) around your fingers, double-tap to zoom
- Page spine scrubber — drag the tab on the right edge to fly through long documents
- Full-text search with highlighted matches and next/previous
- Contents (bookmarks / outline) with a printed-style table of contents
- Turn to page, night pages (warm inverted), keep-screen-awake, immersive tap-to-hide

**Marking up** (written permanently into the PDF on save)
- Pen, highlighter (multiply blend), shapes (rectangle, ellipse, line, arrow)
- Text anywhere on the page (Unicode via the system font)
- Draw-once signature, tap to place it as many times as you like
- Eraser, and full undo / redo — including page edits

**Pages**
- Thumbnail organizer: rotate, reorder, delete, extract to a new PDF
- Insert blank pages, bind in another PDF, add page numbers, stamp a watermark

**Documents**
- Open from the file picker or "Open with" / Share from any app; recent files on the desk
- Merge several PDFs, turn photos into a PDF
- Fill AcroForm fields (text, checkboxes, choices, radio) with optional flattening
- Open password-protected PDFs; save a password-locked copy (AES-128)
- Edit title / author / subject / keywords
- Save in place, save a copy, share, print, export a page as PNG, copy a page's text

## Build it

**Android Studio (recommended):** File → Open → choose this folder, let Gradle sync, press ▶ Run.
Requires Android Studio Ladybug (2024.2) or newer; the app runs on Android 8.0+.

**Command line:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

**No computer? Build on GitHub:** push this folder to a GitHub repository. The included workflow
(`.github/workflows/build.yml`) builds the APK automatically — open the repo's **Actions** tab,
pick the latest run, and download **Vellum-apk**. Install it on your phone (allow "install unknown apps").

## How it's put together

| Piece | Role |
|---|---|
| `pdf/PdfRendererEngine` | Fast page rendering with Android's built-in PdfRenderer (PDFium), cached |
| `pdf/PdfOps` | All editing via PdfBox-Android: markup, pages, forms, merge, encryption, metadata |
| `pdf/TextIndex` | Text + character positions for search and copy |
| `ui/viewer/ViewerViewModel` | Document state, undo/redo (file snapshots + markup), save/export |
| `ui/viewer/Chrome` | Header capsule, search capsule, tool dock, options tray, page spine |
| `ui/theme/Theme` | The Vellum identity: colours, type, shapes |

Edits happen on a private working copy; your original file is only touched when you press **Save**.

## Notes
- Markup is flattened into the page content on save, so it shows in every PDF reader (it is not
  editable as separate annotation objects afterwards).
- Opening a locked PDF removes the password from the working copy; use **Lock a copy** to save it protected again.
- The release build is signed with the debug key for convenience — set up your own signing before publishing.
