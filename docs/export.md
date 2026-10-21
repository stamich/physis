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

## Animated PNG

`Apng.encode(frames, width, height, fps, loops, compress)` writes an animated PNG (APNG) from
frames of equal size. The first frame is also the ordinary image of the file (`IDAT`), the others
are `fdAT` chunks, each with a `fcTL` that shows it for `1/fps` s and replaces the one before;
`acTL` holds the number of frames and of loops (0 plays for ever). Readers that do not know
animation show the first frame, which is how the JDK's `ImageIO` reads it; browsers play it. The
data is stored in uncompressed deflate blocks like a plain PNG, so the bytes depend only on the
frames and the settings; a test compares one file with the bytes of an independent Python
builder. `Apng.write` enlarges each frame like `Png.scale`.

## From a model to an image

- `FrameImage.heatmap(snapshot, view, dots)` draws a `View.Heatmap` with the same colormap, range
  and wall overlay as the Lab; `FrameImage.layers` draws a `View.Layers` (a heatmap with agents
  as dots). It returns a `Frame` (pixels and size) or `None` if the view has no image.
- `FrameImage.withColorBar` adds a scale at the right edge. It carries no text.
- `Frames.render` writes numbered files `frame-00000.png` from a run, every `every` steps;
  `Frames.animation` writes the same frames into one animated PNG (`Options.fps`, `Options.loops`).
- `Montage.tile` joins several frames into one sheet.

## In the Lab

**File → Save image** writes the first image of the current view at 1×, 2×, 4× or 8× to a file
you choose. It is the field as drawn, with a colour scale and the range in the status line. Views
without an image (Lorenz, pendulum) say so instead.

## On the command line

`--frames <dir>` with `--every` and `--scale` writes the numbered frames of a run or a replayed
manifest; `--animate <file>` writes the frames as one animated PNG (`--fps`, `--loops`); `--montage`
writes a sheet from a sweep. See [cli.md](cli.md).
