package physis.view

class StrokePlannerSuite extends munit.FunSuite:
  private def planner(wrap: Boolean = false, r: Double = 8.0) =
    val p = new StrokePlanner(100.0, 60.0, wrap, wrap)
    p.radius = r
    p

  private def gap(a: Dab, b: Dab): Double = math.hypot(a.x - b.x, a.y - b.y)

  test("a stroke starts with a dab at the pointer") {
    val p = planner()
    p.begin(10.25, 20.5)
    assertEquals(p.take(), Vector(Dab(10.25, 20.5)))
    assertEquals(p.take(), Vector.empty)
    assert(p.active)
    p.end()
    assert(!p.active)
  }

  test("a fast drag leaves no gap wider than r / 2 and ends at the pointer") {
    val p = planner()
    p.begin(5.0, 5.0)
    p.extend(85.0, 45.0)
    val dabs = p.take()
    assert(dabs.size > 20)
    assertEquals(dabs.head, Dab(5.0, 5.0))
    assertEqualsDouble(dabs.last.x, 85.0, 1e-12)
    assertEqualsDouble(dabs.last.y, 45.0, 1e-12)
    dabs.zip(dabs.tail).foreach((a, b) => assert(gap(a, b) <= 4.0 + 1e-9, s"$a $b"))
  }

  test("steps along the path are equal and the dabs lie on the segment") {
    val p = planner()
    p.begin(0.0, 0.0)
    p.extend(30.0, 40.0)
    val dabs = p.take()
    val gaps = dabs.zip(dabs.tail).map(gap)
    assert(gaps.forall(g => math.abs(g - gaps.head) < 1e-9))
    dabs.foreach(d => assertEqualsDouble(d.y, d.x * 4.0 / 3.0, 1e-9))
  }

  test("moves shorter than r / 4 are merged until the pointer has gone far enough") {
    val p = planner()
    p.begin(10.0, 10.0)
    p.take()
    p.extend(10.5, 10.0)
    p.extend(11.0, 10.0)
    assertEquals(p.take(), Vector.empty)
    p.extend(12.0, 10.0)
    assertEquals(p.take(), Vector(Dab(12.0, 10.0)))
  }

  test("one batch holds everything planned since the last take") {
    val p = planner()
    p.begin(0.0, 0.0)
    p.extend(20.0, 0.0)
    p.extend(20.0, 20.0)
    val first = p.take()
    assert(first.size >= 11)
    p.extend(20.0, 30.0)
    val second = p.take()
    assert(second.nonEmpty && second.size < first.size)
    assertEqualsDouble(second.last.y, 30.0, 1e-12)
  }

  test("a drag over a periodic seam continues on the other side") {
    val p = planner(wrap = true)
    p.begin(96.0, 30.0)
    p.extend(4.0, 30.0)
    val dabs = p.take()
    assert(dabs.forall(d => d.x >= 0.0 && d.x < 100.0))
    dabs.zip(dabs.tail).foreach { (a, b) =>
      val dx = (a.x - b.x) - 100.0 * math.rint((a.x - b.x) / 100.0)
      assert(math.abs(dx) <= 4.0 + 1e-9, s"$a $b")
    }
    assert(dabs.exists(_.x > 99.0) || dabs.exists(_.x < 1.0))
    assertEqualsDouble(dabs.last.x, 4.0, 1e-9)
    assert(dabs.size <= 5)
  }

  test("without wrapping the drag takes the long way and the positions stay as given") {
    val p = planner(wrap = false)
    p.begin(96.0, 30.0)
    p.extend(4.0, 30.0)
    assert(p.take().size > 20)
  }

  test("a position wraps into the world on a periodic axis") {
    val p = planner(wrap = true)
    p.begin(-3.0, 63.0)
    assertEquals(p.take(), Vector(Dab(97.0, 3.0)))
  }

  test("extend before begin, and after end, does nothing") {
    val p = planner()
    p.extend(10.0, 10.0)
    assertEquals(p.take(), Vector.empty)
    p.begin(0.0, 0.0)
    p.end()
    p.take()
    p.extend(50.0, 0.0)
    assertEquals(p.take(), Vector.empty)
  }

  test("a larger radius spaces dabs wider") {
    def count(r: Double) =
      val p = planner(r = r)
      p.begin(0.0, 0.0)
      p.extend(80.0, 0.0)
      p.take().size
    assert(count(4.0) > count(16.0))
    assertEquals(count(16.0), 11)
  }
