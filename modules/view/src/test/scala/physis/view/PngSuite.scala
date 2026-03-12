package physis.view

import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

class PngSuite extends munit.FunSuite:

  /** Opaque 8 x 4 gradient; its file was also built by an independent Python script. */
  private val opaque: Array[Int] = Array.tabulate(32) { k =>
    val x = k % 8
    val y = k / 8
    0xff000000 | ((x * 32) << 16) | ((y * 64) << 8) | ((x + y) * 16)
  }

  /** The same colours with an alpha that falls from 255 to 199. */
  private val translucent: Array[Int] = Array.tabulate(32) { k =>
    val x = k % 8
    ((255 - 8 * x) << 24) | (opaque(k) & 0xffffff)
  }

  private def crc(bytes: Array[Byte]): Long =
    val c = new CRC32
    c.update(bytes, 0, bytes.length)
    c.getValue

  private def decode(bytes: Array[Byte]): (Array[Int], Int, Int) =
    val image = ImageIO.read(new ByteArrayInputStream(bytes))
    assert(image != null, "ImageIO could not read the file")
    val w = image.getWidth
    val h = image.getHeight
    (Array.tabulate(w * h)(k => image.getRGB(k % w, k / w)), w, h)

  private def sameImage(expected: Array[Int], actual: Array[Int]): Unit =
    assertEquals(actual.length, expected.length)
    for k <- expected.indices do
      // a fully transparent pixel may come back with any colour
      if (expected(k) >>> 24) == 0 then assertEquals(actual(k) >>> 24, 0, s"pixel $k")
      else assertEquals(actual(k), expected(k), s"pixel $k")

  test("the bytes are fixed: golden checksums of two small images") {
    val a = Png.encode(opaque, 8, 4)
    val b = Png.encode(translucent, 8, 4)
    assertEquals(a.length, 168)
    assertEquals(crc(a), 0x57566b69L)
    assertEquals(b.length, 200)
    assertEquals(crc(b), 0x1c87f55bL)
  }

  test("the file has the PNG signature, a header, one data chunk and an end chunk") {
    val bytes = Png.encode(opaque, 8, 4)
    assertEquals(
      bytes.take(8).toVector,
      Vector(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
    )
    assertEquals(new String(bytes, 12, 4, "US-ASCII"), "IHDR")
    assertEquals(bytes(19), 8.toByte) // width 8
    assertEquals(bytes(23), 4.toByte) // height 4
    assertEquals(bytes(24), 8.toByte) // bit depth
    assertEquals(bytes(25), 2.toByte) // RGB: no pixel is translucent
    assertEquals(Png.encode(translucent, 8, 4)(25), 6.toByte) // RGBA
    assertEquals(new String(bytes, bytes.length - 8, 4, "US-ASCII"), "IEND")
    assertEquals(new String(bytes, 37, 4, "US-ASCII"), "IDAT")
  }

  test("an image reads back as it was written, opaque or not") {
    for pixels <- Seq(opaque, translucent) do
      val (back, w, h) = decode(Png.encode(pixels, 8, 4))
      assertEquals((w, h), (8, 4))
      sameImage(pixels, back)
  }

  test("a large noisy image spans several stored blocks and still reads back") {
    val random = new java.util.Random(7L)
    val w = 301
    val h = 257
    val pixels = Array.fill(w * h)((random.nextInt(3) match
      case 0 => 0xff
      case 1 => 0x80
      case _ => 0x00
    ) << 24 | (random.nextInt() & 0xffffff))
    val bytes = Png.encode(pixels, w, h)
    assert(bytes.length > 3 * 0xffff)
    val (back, bw, bh) = decode(bytes)
    assertEquals((bw, bh), (w, h))
    sameImage(pixels, back)
  }

  test("the same pixels always give the same bytes") {
    assertEquals(
      Png.encode(translucent, 8, 4).toVector,
      Png.encode(translucent.clone(), 8, 4).toVector
    )
  }

  test("a compressed file is smaller for a flat image and reads back the same") {
    val flat = Array.fill(200 * 100)(0xff3070a0)
    val plain = Png.encode(flat, 200, 100)
    val packed = Png.encode(flat, 200, 100, compress = true)
    assert(packed.length < plain.length / 10, s"${packed.length} vs ${plain.length}")
    sameImage(flat, decode(packed)._1)
  }

  test("scaling repeats each pixel in both directions") {
    val small = Array(0xff000001, 0xff000002, 0xff000003, 0xff000004, 0xff000005, 0xff000006)
    val big = Png.scale(small, 3, 2, 2)
    assertEquals(big.length, 24)
    assertEquals(big.take(6).toVector, Vector(1, 1, 2, 2, 3, 3).map(0xff000000 | _))
    assertEquals(big.slice(6, 12).toVector, big.take(6).toVector)
    assertEquals(big.slice(12, 18).toVector, Vector(4, 4, 5, 5, 6, 6).map(0xff000000 | _))
    assert(Png.scale(small, 3, 2, 1) eq small)
  }

  test("an exported image is the scaled one") {
    val path = java.nio.file.Files.createTempFile("physis-png", ".png")
    Png.write(path, opaque, 8, 4, factor = 3)
    val (back, w, h) = decode(java.nio.file.Files.readAllBytes(path))
    assertEquals((w, h), (24, 12))
    sameImage(Png.scale(opaque, 8, 4, 3), back)
    java.nio.file.Files.delete(path)
  }

  test("bad sizes are refused") {
    intercept[IllegalArgumentException](Png.encode(opaque, 0, 4))
    intercept[IllegalArgumentException](Png.encode(opaque, 8, 5))
    intercept[IllegalArgumentException](Png.scale(opaque, 8, 4, 0))
  }
