package physis.view

import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

class ApngSuite extends munit.FunSuite:

  /** One chunk of a PNG file as the parser below reads it. */
  private final case class Chunk(kind: String, data: Array[Byte], crcOk: Boolean)

  private def int(b: Array[Byte], at: Int): Int =
    ((b(at) & 0xff) << 24) | ((b(at + 1) & 0xff) << 16) | ((b(at + 2) & 0xff) << 8) | (b(
      at + 3
    ) & 0xff)

  private def short(b: Array[Byte], at: Int): Int = ((b(at) & 0xff) << 8) | (b(at + 1) & 0xff)

  /** Splits a file into chunks, checking the signature and that nothing is left over. */
  private def chunks(file: Array[Byte]): Vector[Chunk] =
    assertEquals(file.take(8).toVector, Png.Signature.toVector)
    var at = 8
    val out = Vector.newBuilder[Chunk]
    while at < file.length do
      val length = int(file, at)
      val kind = new String(file, at + 4, 4, "US-ASCII")
      val data = file.slice(at + 8, at + 8 + length)
      val crc = new CRC32
      crc.update(file, at + 4, 4 + length)
      out += Chunk(kind, data, crc.getValue.toInt == int(file, at + 8 + length))
      at += 12 + length
    assertEquals(at, file.length, "bytes after the last chunk")
    out.result()

  /** Opaque 8 x 4 frames; the same pattern as the golden file of tools-free Python builder. */
  private def frame(f: Int): Array[Int] = Array.tabulate(32) { k =>
    val x = k % 8
    val y = k / 8
    (0xff000000 | ((x * 32) << 16) | ((y * 64) << 8) | ((x + y) * 16)) ^ (f * 0x00101010)
  }

  private val frames: IndexedSeq[Array[Int]] = Vector(frame(0), frame(1), frame(2))

  private def crc(bytes: Array[Byte]): Long =
    val c = new CRC32
    c.update(bytes, 0, bytes.length)
    c.getValue

  private def decode(bytes: Array[Byte]): Array[Int] =
    val image = ImageIO.read(new ByteArrayInputStream(bytes))
    assert(image != null, "ImageIO could not read the data")
    Array.tabulate(image.getWidth * image.getHeight)(k =>
      image.getRGB(k % image.getWidth, k / image.getWidth)
    )

  test("the bytes are fixed: they equal an independent Python builder's file") {
    val file = Apng.encode(frames, 8, 4, fps = 10, loops = 2)
    assertEquals(file.length, 556)
    assertEquals(crc(file), 0x6124d6fbL)
  }

  test("the chunks come in the order APNG wants, with valid checksums") {
    val parts = chunks(Apng.encode(frames, 8, 4, fps = 10, loops = 2))
    assertEquals(
      parts.map(_.kind),
      Vector("IHDR", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "fcTL", "fdAT", "IEND")
    )
    assert(parts.forall(_.crcOk))
  }

  test("acTL holds the number of frames and of plays; every fcTL the size and the delay") {
    val parts = chunks(Apng.encode(frames, 8, 4, fps = 10, loops = 2))
    val actl = parts(1).data
    assertEquals((int(actl, 0), int(actl, 4)), (3, 2))
    val controls = parts.filter(_.kind == "fcTL").map(_.data)
    for c <- controls do
      assertEquals(c.length, 26)
      assertEquals((int(c, 4), int(c, 8), int(c, 12), int(c, 16)), (8, 4, 0, 0))
      assertEquals((short(c, 20), short(c, 22)), (1, 10), "delay 1/10 s")
      assertEquals((c(24).toInt, c(25).toInt), (0, 0), "dispose none, blend source")
  }

  test("sequence numbers run from 0 through fcTL and fdAT, with no gap") {
    val parts = chunks(Apng.encode(frames, 8, 4))
    val numbers = parts.filter(c => c.kind == "fcTL" || c.kind == "fdAT").map(c => int(c.data, 0))
    assertEquals(numbers, Vector(0, 1, 2, 3, 4))
  }

  test("the first frame is the ordinary image: ImageIO decodes it") {
    val file = Apng.encode(frames, 8, 4)
    val decoded = decode(file)
    assertEquals(decoded.toVector, frames(0).toVector)
  }

  test("every frame decodes: the fdAT data of a frame is the zlib stream of its scanlines") {
    val file = Apng.encode(frames, 8, 4)
    val parts = chunks(file)
    val header = parts.head.data
    for (c, k) <- parts.filter(_.kind == "fdAT").zipWithIndex do
      // a standalone PNG made of the header and this frame's data as IDAT
      val single = new java.io.ByteArrayOutputStream()
      single.write(Png.Signature)
      Png.chunk(single, "IHDR", header)
      Png.chunk(single, "IDAT", c.data.drop(4))
      Png.chunk(single, "IEND", Array.emptyByteArray)
      assertEquals(decode(single.toByteArray).toVector, frames(k + 1).toVector, s"frame ${k + 1}")
  }

  test("a translucent pixel in any frame makes every frame RGBA") {
    val soft = frames.updated(2, frames(2).updated(5, 0x80112233))
    val parts = chunks(Apng.encode(soft, 8, 4))
    assertEquals(parts.head.data(9).toInt, 6)
    assertEquals(chunks(Apng.encode(frames, 8, 4)).head.data(9).toInt, 2)
    assertEquals(decode(Apng.encode(soft, 8, 4)).toVector, frames(0).toVector)
  }

  test("compression keeps the pictures and changes only the data") {
    val big = Vector.tabulate(3)(f => Array.fill(64 * 64)(0xff000000 | (f * 40 << 8)))
    val plain = Apng.encode(big, 64, 64)
    val packed = Apng.encode(big, 64, 64, compress = true)
    assert(packed.length < plain.length / 4, s"${packed.length} vs ${plain.length}")
    assertEquals(decode(packed).toVector, big(0).toVector)
    assertEquals(chunks(packed).map(_.kind), chunks(plain).map(_.kind))
    assert(chunks(packed).forall(_.crcOk))
  }

  test("a frame larger than a stored block spans several blocks and still decodes") {
    val w = 200
    val h = 120 // 72 000 bytes of scanlines: two stored blocks
    val big =
      Vector.tabulate(2)(f => Array.tabulate(w * h)(k => 0xff000000 | ((k * (f + 3)) & 0xffffff)))
    val file = Apng.encode(big, w, h)
    assertEquals(decode(file).toVector, big(0).toVector)
    assert(chunks(file).forall(_.crcOk))
  }

  test("the same frames and settings always give the same bytes; another delay changes them") {
    val a = Apng.encode(frames, 8, 4, fps = 10)
    assertEquals(a.toVector, Apng.encode(frames, 8, 4, fps = 10).toVector)
    assertNotEquals(crc(a), crc(Apng.encode(frames, 8, 4, fps = 11)))
    assertNotEquals(crc(a), crc(Apng.encode(frames, 8, 4, fps = 10, loops = 1)))
  }

  test("one frame is a valid animation of one frame") {
    val parts = chunks(Apng.encode(Vector(frame(0)), 8, 4))
    assertEquals(parts.map(_.kind), Vector("IHDR", "acTL", "fcTL", "IDAT", "IEND"))
    assertEquals(int(parts(1).data, 0), 1)
  }

  test("write enlarges every frame and the file can be read back") {
    val file = java.nio.file.Files.createTempFile("physis-apng", ".png")
    try
      Apng.write(file, frames, 8, 4, fps = 5, loops = 1, factor = 3)
      val bytes = java.nio.file.Files.readAllBytes(file)
      val parts = chunks(bytes)
      assertEquals(int(parts.head.data, 0), 24)
      assertEquals(int(parts.head.data, 4), 12)
      val expected = Png.scale(frames(0), 8, 4, 3)
      assertEquals(decode(bytes).toVector, expected.toVector)
    finally
      val _ = java.nio.file.Files.deleteIfExists(file)
  }

  test("it refuses what cannot be written, with a message") {
    def refused(body: => Any): String =
      intercept[IllegalArgumentException](body).getMessage
    assert(refused(Apng.encode(Vector.empty, 8, 4)).contains("at least one frame"))
    assert(refused(Apng.encode(frames, 0, 4)).contains("positive"))
    assert(refused(Apng.encode(frames, 8, 4, fps = 0)).contains("frames per second"))
    assert(refused(Apng.encode(frames, 8, 4, fps = 70000)).contains("frames per second"))
    assert(refused(Apng.encode(frames, 8, 4, loops = -1)).contains("loops"))
    assert(refused(Apng.encode(frames.updated(1, new Array[Int](3)), 8, 4)).contains("frame 1"))
  }
