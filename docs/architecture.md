# ChaoScan Architecture

This project is a lightweight CamScanner-style Android scanner without OCR.

## Reuse Boundary

- Document scanner entry: Google ML Kit Document Scanner handles the mature camera/gallery scan flow, page detection, and crop UI without bundling an OCR engine.
- Scan filter rendering: GPUImage handles GPU-backed brightness, exposure, contrast, gamma, saturation, sharpen, and grayscale filters.
- PDF output: Android framework `PdfDocument` writes PDFs without a large PDF dependency.

OpenCV, Tesseract, and OCR SDKs are intentionally excluded because they would add binary size and are outside the requested scope.

## Main Flow

1. `GalleryRepository` queries `MediaStore` after the image-read permission is granted.
2. `MainActivity` keeps selected gallery images in a `LinkedHashMap`, so the visible selection number is the import order.
3. Drag selection is implemented on the `RecyclerView` touch layer. A drag that starts on an unselected image adds every visited image; a drag that starts on a selected image removes every visited image.
4. Imported images become `DocumentPage` objects. The editor keeps one ordered list, and `ItemTouchHelper` moves pages by dragging thumbnails.
5. `ScanParameters` is global for the current document. Preview and export use the same renderer, so export does not silently diverge from what the user tuned.
6. `PdfExporter` decodes each page with a maximum long edge, applies rotation and filters, then draws it into the selected PDF page size.

## Error Policy

The app intentionally surfaces decode, GPU render, scanner, and PDF write failures. It does not switch to a hidden CPU fallback because that would mask the broken main path.
