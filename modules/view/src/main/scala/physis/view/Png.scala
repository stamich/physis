package physis.view

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import java.util.zip.{Adler32, CRC32, Deflater}

/**
 * A PNG writer for packed ARGB pixels, with no toolkit behind it.
 *
 * The bytes depend only on the pixels: the image data is stored in uncompressed deflate blocks,
 * which no zlib version can change, so the same image gives the same file on every platform
 * (frames of a replayed session can be compared byte for byte). Fully opaque images are written
 * as RGB, others as RGBA. Pass `compress = true` for smaller files; then the bytes are those of
 * the zlib the JVM runs on.
 */
object Png:
  private val Signature: Array[Byte] =
    Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  /** The largest block a stored deflate block can hold. */
  private val StoredBlock = 0xffff

  /** The file as bytes; `pixels` holds `width * height` ARGB values, row 0 first. */
  def encode(
      pixels: Array[Int],
      width: Int,
      height: Int,
      compress: Boolean = false
  ): Array[Byte] =
    require(width >= 1 && height >= 1, s"image size must be positive, got $width x $height")
    val count = width.toLong * height
    require(pixels.length >= count, s"$width x $height needs $count pixels, got ${pixels.length}")
    var opaque = true
    var i = 0
    while opaque && i < count do
      if (pixels(i) >>> 24) != 0xff then opaque = false
      i += 1
    val bytesPerPixel = if opaque then 3 else 4
    val rowBytes = 1 + width.toLong * bytesPerPixel
    require(rowBytes * height < Int.MaxValue / 2, s"a $width x $height image is too large")
    val raw = new Array[Byte]((rowBytes * height).toInt)
    var o = 0
    var y = 0
    while y < height do
      raw(o) = 0 // filter type: none
      o += 1
      var k = y * width
      var x = 0
      while x < width do
        val p = pixels(k)
        raw(o) = (p >> 16).toByte
        raw(o + 1) = (p >> 8).toByte
        raw(o + 2) = p.toByte
        if bytesPerPixel == 4 then raw(o + 3) = (p >>> 24).toByte
        o += bytesPerPixel
        k += 1
        x += 1
      y += 1
    val out = new ByteArrayOutputStream(raw.length + 1024)
    out.write(Signature, 0, Signature.length)
    chunk(out, "IHDR", header(width, height, if opaque then 2 else 6))
    chunk(out, "IDAT", if compress then deflated(raw) else stored(raw))
    chunk(out, "IEND", Array.emptyByteArray)
    out.toByteArray

  /** Writes the image to `path`, enlarged `factor` times (see [[scale]]). */
  def write(
      path: Path,
      pixels: Array[Int],
      width: Int,
      height: Int,
      factor: Int = 1,
      compress: Boolean = false
  ): Unit =
    val bytes =
      encode(scale(pixels, width, height, factor), width * factor, height * factor, compress)
    Files.write(path, bytes)
    ()

  /** Each pixel repeated `factor` times in both directions (nearest neighbour). */
  def scale(pixels: Array[Int], width: Int, height: Int, factor: Int): Array[Int] =
    require(factor >= 1, s"scale factor must be at least 1, got $factor")
    require(pixels.length >= width.toLong * height, "not enough pixels for the stated size")
    if factor == 1 then pixels
    else
      val w = width * factor
      val out = new Array[Int](w * (height * factor))
      var y = 0
      while y < height * factor do
        val source = (y / factor) * width
        val target = y * w
        var x = 0
        while x < w do
          out(target + x) = pixels(source + x / factor)
          x += 1
        y += 1
      out

  private def header(width: Int, height: Int, colorType: Int): Array[Byte] =
    val h = new Array[Byte](13)
    put(h, 0, width)
    put(h, 4, height)
    h(8) = 8 // bit depth
    h(9) = colorType.toByte
    h // compression, filter and interlace methods are 0

  private def put(a: Array[Byte], at: Int, v: Int): Unit =
    a(at) = (v >>> 24).toByte
    a(at + 1) = (v >>> 16).toByte
    a(at + 2) = (v >>> 8).toByte
    a(at + 3) = v.toByte

  private def chunk(out: ByteArrayOutputStream, kind: String, data: Array[Byte]): Unit =
    val length = new Array[Byte](4)
    put(length, 0, data.length)
    out.write(length, 0, 4)
    val type4 = kind.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    out.write(type4, 0, 4)
    out.write(data, 0, data.length)
    val crc = new CRC32
    crc.update(type4, 0, 4)
    crc.update(data, 0, data.length)
    val tail = new Array[Byte](4)
    put(tail, 0, crc.getValue.toInt)
    out.write(tail, 0, 4)

  /** A zlib stream of uncompressed deflate blocks. */
  private def stored(raw: Array[Byte]): Array[Byte] =
    val blocks = (raw.length + StoredBlock - 1) / StoredBlock
    val out = new Array[Byte](2 + raw.length + 5 * blocks + 4)
    out(0) = 0x78
    out(1) = 0x01 // (0x78 * 256 + 1) is a multiple of 31, as the format wants
    var pos = 2
    var offset = 0
    while offset < raw.length do
      val n = math.min(StoredBlock, raw.length - offset)
      out(pos) = if offset + n == raw.length then 1 else 0
      out(pos + 1) = n.toByte
      out(pos + 2) = (n >> 8).toByte
      out(pos + 3) = (~n).toByte
      out(pos + 4) = ((~n) >> 8).toByte
      System.arraycopy(raw, offset, out, pos + 5, n)
      pos += 5 + n
      offset += n
    val adler = new Adler32
    adler.update(raw, 0, raw.length)
    put(out, pos, adler.getValue.toInt)
    out

  private def deflated(raw: Array[Byte]): Array[Byte] =
    val deflater = new Deflater(6)
    try
      deflater.setInput(raw)
      deflater.finish()
      val out = new ByteArrayOutputStream(raw.length / 2 + 64)
      val buffer = new Array[Byte](65536)
      while !deflater.finished() do
        val n = deflater.deflate(buffer)
        out.write(buffer, 0, n)
      out.toByteArray
    finally deflater.end()
