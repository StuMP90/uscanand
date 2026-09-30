# uScanAnd

An Android document scanner. It captures pages with the camera, detects the page edges and straightens them, and puts them together into a PDF. On-device text recognition can make the PDF searchable.

## Features

- **Scan** with Google's ML Kit Document Scanner: automatic edge detection, perspective correction, retakes, and import from the gallery.
- **Import** existing photos as pages. EXIF orientation is applied.
- **Document library**: keep, rename and delete scans, and add pages to them later.
- **Page editing**: reorder, rotate, delete, re-crop by dragging the four corners (with a perspective warp), and filters (Original, Enhanced, Greyscale, Black & white). Edits never change the stored original.
- **Flatten page**: straightens curled or wavy paper, per page or for all pages at once. It levels the lines of text found by OCR and the printed rules of tables, and makes table columns upright. It also pulls the paper's visible edges out to the image border, which removes the wavy strips of desk that show around a curled sheet.
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
| Flattening curled pages | `image/FlattenSolver.kt` (solver and mesh), `image/PaperEdgeDetector.kt`, `image/RuleDetector.kt`, `ImageProcessor.applyFlatten` (warp) |
| PDF output | `pdf/PdfWriter.kt` |
| Export pipeline, including OCR | `pdf/PdfExporter.kt`, `pdf/ExportManager.kt` |

The PDF writer is small and hand-written. It embeds each page's JPEG unchanged (DCTDecode) and writes recognised text in text render mode 3 (invisible), sized to match each line's bounding box. Unlike Android's `PdfDocument`, this keeps files small and makes the text layer possible.

The Enhanced and Black & white filters estimate the paper colour across the page (downscale, then max-pool), divide it out to remove shadows and uneven lighting, then boost contrast or apply a threshold.

Flatten page works like this:

1. It fits a curve through each line of words found by ML Kit.
2. It traces long printed rules, such as table borders and underlines, horizontal and vertical.
3. It traces the paper's edges wherever darker background shows around the sheet.
4. It solves for two warps over a 32×44 mesh, using a banded least-squares solver, and adds them together:
   - **Line warp:** levels every text line and horizontal rule, and makes vertical rules upright. Only differences along each line are constrained, so separate columns and table cells stay aligned. The page also keeps its scale, so OCR noise can't squash it.
   - **Edge warp:** pins the paper edges to the image border. It may bend only in a narrow band next to each detected edge; across the rest of the page it can only shift or stretch evenly, and it may not bend any text line or rule. So the edges tidy the border, but only the text and rules decide how the page is tilted.
5. The page is redrawn with `Canvas.drawBitmapMesh`.

The mesh is saved beside the page as `<page>_mesh.bin`, so previews and exported PDFs match. Each page records which version of the flattening produced its mesh. Pages flattened by an earlier version still render, and running Flatten all pages redoes them with the current version.

## Limitations

- Flatten page needs a reasonable amount of text, some long printed rules, or a visible paper edge; otherwise the page is left unchanged rather than guessed at. It corrects smooth curls, but not sharp creases or folds.

- OCR supports Latin-script text, and the text layer uses PDF WinAnsi encoding, so characters outside it become `?`. This affects searching and copying only; the page image is always exact.
- On first use, Play services may still be downloading the text recognition model. If so, the export continues without a text layer and the app says so.
