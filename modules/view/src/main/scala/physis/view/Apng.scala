package physis.view

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}

/**
 * An animated PNG writer for packed ARGB frames, with no toolkit behind it.
 *
 * The file is a PNG whose first frame is also its ordinary image, so a viewer that does not know
 * animation (or the JDK's ImageIO) shows the first frame; the others follow in `fdAT` chunks
 * after `acTL` (frame count and loops) and one `fcTL` (size, delay) per frame. Frames replace
 * each other completely: no offsets, no disposal, no blending.
 *
 * Like [[Png]], the bytes depend only on the pixels and the settings, because the image data is
 * stored in uncompressed deflate blocks; `compress = true` gives smaller files whose bytes are
 * those of the zlib the JVM runs on. The frames are RGB if every pixel of every frame is opaque,
 * else RGBA.
 */
object Apng:
  /** The file as bytes; each frame holds `width * height` ARGB values, row 0 first. */
  def encode(
      frames: IndexedSeq[Array[Int]],
      width: Int,
      height: Int,
      fps: Int = 12,
      loops: Int = 0,
      compress: Boolean = false
  ): Array[Byte] =
    require(frames.nonEmpty, "an animation needs at least one frame")
    require(width >= 1 && height >= 1, s"image size must be positive, got $width x $height")
    require(fps >= 1 && fps <= 65535, s"frames per second must be 1 to 65535, got $fps")
    require(loops >= 0, s"loops must not be negative (0 is for ever), got $loops")
    val count = width.toLong * height
    frames.zipWithIndex.foreach { (f, k) =>
      require(f.length >= count, s"frame $k: $width x $height needs $count pixels, got ${f.length}")
    }
    val opaque = frames.forall(Png.isOpaque(_, count))
    val bytesPerPixel = if opaque then 3 else 4
    val out = new ByteArrayOutputStream(1024 + frames.size * (count.toInt * bytesPerPixel + 64))
    out.write(Png.Signature, 0, Png.Signature.length)
    Png.chunk(out, "IHDR", Png.header(width, height, if opaque then 2 else 6))
    val control = new Array[Byte](8)
    Png.put(control, 0, frames.size)
    Png.put(control, 4, loops)
    Png.chunk(out, "acTL", control)
    var sequence = 0
    var k = 0
    while k < frames.size do
      Png.chunk(out, "fcTL", frameControl(sequence, width, height, fps))
      sequence += 1
      val raw = Png.scanlines(frames(k), width, height, bytesPerPixel)
      val data = if compress then Png.deflated(raw) else Png.stored(raw)
      if k == 0 then Png.chunk(out, "IDAT", data)
      else
        val framed = new Array[Byte](4 + data.length)
        Png.put(framed, 0, sequence)
        System.arraycopy(data, 0, framed, 4, data.length)
        Png.chunk(out, "fdAT", framed)
        sequence += 1
      k += 1
    Png.chunk(out, "IEND", Array.emptyByteArray)
    out.toByteArray

  /** Writes the animation to `path`, each frame enlarged `factor` times (see [[Png.scale]]). */
  def write(
      path: Path,
      frames: IndexedSeq[Array[Int]],
      width: Int,
      height: Int,
      fps: Int = 12,
      loops: Int = 0,
      factor: Int = 1,
      compress: Boolean = false
  ): Unit =
    val scaled = frames.map(Png.scale(_, width, height, factor))
    Files.write(path, encode(scaled, width * factor, height * factor, fps, loops, compress))
    ()

  /** The `fcTL` payload: a full-size frame at the origin, shown for `1 / fps` s, source blend. */
  private def frameControl(sequence: Int, width: Int, height: Int, fps: Int): Array[Byte] =
    val c = new Array[Byte](26)
    Png.put(c, 0, sequence)
    Png.put(c, 4, width)
    Png.put(c, 8, height)
    // x and y offsets (bytes 12 to 19) stay 0
    c(21) = 1 // delay numerator, low byte (the high byte is 0)
    c(22) = (fps >> 8).toByte // delay denominator
    c(23) = fps.toByte
    // dispose_op and blend_op (bytes 24 and 25) stay 0: replace the whole frame
    c
