package physis.view

class MontageSuite extends munit.FunSuite:
  private def frame(value: Int, w: Int = 2, h: Int = 2, lo: Double = 0.0, hi: Double = 1.0) =
    Some(new Frame(w, h, Array.fill(w * h)(value), lo, hi))

  test("tiles go row by row from the top left, with the gap between them") {
    val m = Montage
      .tile(Vector(frame(1), frame(2), frame(3), frame(4), frame(5)), 3, gap = 1, background = 9)
      .fold(e => fail(e), identity)
    assertEquals((m.width, m.height), (3 * 2 + 2, 2 * 2 + 1))
    def row(y: Int) = m.pixels.slice(y * m.width, (y + 1) * m.width).toVector
    assertEquals(row(0), Vector(1, 1, 9, 2, 2, 9, 3, 3))
    assertEquals(row(1), row(0))
    assertEquals(row(2), Vector.fill(8)(9))
    assertEquals(row(3), Vector(4, 4, 9, 5, 5, 9, 9, 9))
    assertEquals(row(4), row(3))
  }

  test("a missing tile stays blank, and the range covers the tiles") {
    val m = Montage
      .tile(Vector(frame(1, lo = -2.0, hi = 1.0), None, frame(3, lo = 0.0, hi = 5.0)), 3, 0, 7)
      .fold(e => fail(e), identity)
    assertEquals(m.pixels.take(6).toVector, Vector(1, 1, 7, 7, 3, 3))
    assertEquals((m.lo, m.hi), (-2.0, 5.0))
  }

  test("fewer tiles than columns make a narrower picture") {
    val m = Montage.tile(Vector(frame(1), frame(2)), 5, 2).fold(e => fail(e), identity)
    assertEquals((m.width, m.height), (2 * 2 + 2, 2))
  }

  test("empty montages, mixed sizes and bad arguments are refused") {
    assert(Montage.tile(Vector(None, None), 2).isLeft)
    assert(Montage.tile(Vector.empty, 2).isLeft)
    assert(Montage.tile(Vector(frame(1), frame(2, w = 3)), 2).left.exists(_.contains("size")))
    assert(Montage.tile(Vector(frame(1)), 0).isLeft)
    assert(Montage.tile(Vector(frame(1)), 1, gap = -1).isLeft)
  }
