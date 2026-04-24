package physis.view

import java.io.OutputStream

/**
 * The streaming writer holds one frame, not all of them: 300 frames of 512 x 512 (about 235 MB
 * of image data in uncompressed blocks) go to a sink that keeps nothing, and the heap after a
 * collection has not grown by more than a few frames.
 */
class ApngStreamSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private final class Counting extends OutputStream:
    var bytes = 0L
    override def write(b: Int): Unit = bytes += 1
    override def write(b: Array[Byte], off: Int, len: Int): Unit = bytes += len

  private def used(): Long =
    System.gc()
    val r = Runtime.getRuntime
    r.totalMemory - r.freeMemory

  test("300 frames of 512 x 512 are streamed without keeping them") {
    val n = 512
    val frames = 300
    val pixels = Array.tabulate(n * n)(k => 0xff000000 | (k & 0xffffff))
    val sink = new Counting
    val before = used()
    val start = System.nanoTime()
    val w = new Apng.Writer(sink, n, n, frames, fps = 30)
    var k = 0
    while k < frames do
      pixels(0) = 0xff000000 | k // a different first pixel for every frame
      w.add(pixels)
      k += 1
    w.finish()
    val seconds = (System.nanoTime() - start) / 1e9
    val grown = used() - before
    assert(sink.bytes > frames.toLong * n * n * 3, s"only ${sink.bytes} bytes written")
    assert(grown < 64L * 1024 * 1024, s"the heap grew by ${grown / 1024 / 1024} MB")
    println(
      f"apng stream: $frames frames of $n x $n in $seconds%.1f s, heap grew ${grown / 1024}%d KB"
    )
  }
