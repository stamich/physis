# Exporting images

Physis writes PNG files with its own writer (`physis.view.Png`), with no toolkit behind it, so
the same code serves the Lab, the command line and the tests.

## The writer

`Png.encode(pixels, width, height, compress)` takes packed ARGB pixels, row 0 first. Fully opaque
images are written as RGB, the rest as RGBA. By default the image data is stored in
**uncompressed deflate blocks**, so the bytes depend only on the pixels: no zlib version can
change them, and frames of a replayed session can be compared byte for byte. `compress = true`
uses the JVM's zlib for smaller files (same pixels, platform-dependent bytes). `Png.scale` repeats
every pixel in both directions. A 512 × 512 image takes well under 30 ms (slow test).

## From a model to an image

- `FrameImage.heatmap(snapshot, view, dots)` draws a `View.Heatmap` with the same colormap, range
  and wall overlay as the Lab; `FrameImage.layers` draws a `View.Layers` (a heatmap with agents
  as dots). It returns a `Frame` (pixels and size) or `None` if the view has no image.
- `FrameImage.withColorBar` adds a scale at the right edge. It carries no text.
- `Frames.render` writes numbered files `frame-00000.png` from a run, every `every` steps.
- `Montage.tile` joins several frames into one sheet.

## In the Lab

**File → Save image** writes the first image of the current view at 1×, 2×, 4× or 8× to a file
you choose. It is the field as drawn, with a colour scale and the range in the status line. Views
without an image (Lorenz, pendulum) say so instead.

## On the command line

`--frames <dir>` with `--every` and `--scale` writes the numbered frames of a run or a replayed
manifest; `--montage` writes a sheet from a sweep. See [cli.md](cli.md).
