# uScanAnd

An Android document scanner. It captures pages with the camera, detects the page edges and straightens them, and puts them together into a PDF. On-device text recognition can make the PDF searchable.

## Features

- **Scan** with Google's ML Kit Document Scanner: automatic edge detection, perspective correction, retakes, and import from the gallery.
- **Import** existing photos as pages. EXIF orientation is applied.
- **Document library**: keep, rename and delete scans, and add pages to them later.
- **Page editing**: reorder, rotate, delete, re-crop by dragging the four corners (with a perspective warp), and filters (Original, Enhanced, Greyscale, Black & white). Edits never change the stored original.
- **Searchable PDF**: ML Kit text recognition adds an invisible text layer, so you can search and copy text from the PDF.
- **Export**: share through the Android share sheet, or save to any location (Downloads, Drive, …).
- **Settings**: page size (A4, US Letter, or fit to image), image quality, and OCR on or off.

## Requirements

- Android 8.0 (API 26) or newer.
- Google Play services. The scanner and text recognition models come through Play services. On a phone without them, the scanner can't open and the app shows a message.

## Building

Needs JDK 17 and the Android SDK (platform 35):

```sh
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM unit tests (PDF writer, crop geometry)
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk (minified)
```

The debug build installs as a separate app, **uScanAnd Debug** (`uk.co.dsv1.uscanand.debug`), so testing never touches the scans in the real app.

Release builds are signed using `keystore.properties` in the project root. It isn't in git and looks like this:

```properties
storeFile=/path/to/uscanand-release.jks
storePassword=…
keyAlias=uscanand
keyPassword=…
```

Without that file, release builds are unsigned. Keep the keystore safe: an update must be signed with the same key, or the app has to be uninstalled first, which deletes its scans.

## How it works

| Area | Where |
| --- | --- |
| Scanner and photo picker hooks | `ui/Capture.kt` |
| Storage: one folder per document with `document.json`, the original images and previews | `data/DocumentRepository.kt` |
| Crop warp, rotation and filters | `image/ImageProcessor.kt`, `image/QuadMath.kt` |
| PDF output | `pdf/PdfWriter.kt` |
| Export pipeline, including OCR | `pdf/PdfExporter.kt`, `pdf/ExportManager.kt` |

The PDF writer is small and hand-written. It embeds each page's JPEG unchanged (DCTDecode) and writes recognised text in text render mode 3 (invisible), sized to match each line's bounding box. Unlike Android's `PdfDocument`, this keeps files small and makes the text layer possible.

The Enhanced and Black & white filters estimate the paper colour across the page (downscale, then max-pool), divide it out to remove shadows and uneven lighting, then boost contrast or apply a threshold.

## Limitations

- OCR supports Latin-script text, and the text layer uses PDF WinAnsi encoding, so characters outside it become `?`. This affects searching and copying only; the page image is always exact.
- On first use, Play services may still be downloading the text recognition model. If so, the export continues without a text layer and the app says so.
