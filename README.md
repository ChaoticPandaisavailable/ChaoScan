# ChaoScan

ChaoScan is a lightweight Android document scanner for turning photos and PDFs into clean scan-like files. It focuses on fast batch workflows: import many images, crop and reorder pages, tune scan filters, save drafts, and export PDF/JPG/PNG files.

The app intentionally does not include OCR. The goal is a smaller, faster scanner that keeps the main document-processing path easy to inspect and debug.

## Features

- Import images from the system gallery with ordered multi-select.
- Long-press drag rectangle selection for quickly selecting many photos.
- Import PDF pages for continued crop, rotation, filter, and export work.
- Camera scan entry through Google ML Kit Document Scanner.
- Per-page and batch operations: crop, smart crop, rotate, delete, reorder, and apply filters.
- Scan-style filters for notes and documents, including black-and-white scan, color enhancement, note color, invert, and detailed manual parameters.
- Draft saving with multiple named drafts.
- Export to PDF, JPG, or PNG with page range, page size, quality, progress, and preview controls.

## Design Goals

- Keep APK size small.
- Reuse mature Android/GitHub libraries where they make sense.
- Avoid OCR, Tesseract, OpenCV, and large model weights in the default build.
- Surface real decode/render/export errors instead of hiding broken main paths behind fallback behavior.

## Tech Stack

- Kotlin
- Android framework views
- Google ML Kit Document Scanner
- PictureSelector
- GPUImage
- Android `PdfDocument`

## Build

Open the project in Android Studio, let Gradle sync, then build the `app` module.

Command-line build with a local Gradle 8.9+ installation:

```powershell
gradle --no-daemon :app:assembleRelease
```

The release APK is generated at:

```text
app/build/outputs/apk/release/app-release.apk
```

## Repository Notes

Large local folders such as Android SDK tooling, downloaded model experiments, Gradle caches, and generated APKs are intentionally ignored. Put installable APKs in GitHub Releases instead of committing them to the repository.

## License

No license has been selected yet.
