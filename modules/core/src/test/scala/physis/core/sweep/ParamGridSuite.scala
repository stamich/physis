package physis.core.sweep

import physis.core.param.DemoParams

class ParamGridSuite extends munit.FunSuite:
  private val schema = DemoParams.schema

  private def grid(specs: String*): ParamGrid =
    ParamGrid.parse(specs, schema).fold(e => fail(e), identity)

  private def error(specs: String*): String =
    ParamGrid.parse(specs, schema) match
      case Left(message) => message
      case Right(g) => fail(s"accepted: ${g.axes}")

  test("a linear axis includes both ends") {
    assertEquals(grid("rate=0:1:5").axes.head.values, Vector(0.0, 0.25, 0.5, 0.75, 1.0))
  }

  test("a logarithmic axis is evenly spaced in the logarithm and ends exactly") {
    val values = grid("rate=log:0.01:10:4").axes.head.values
    assertEquals(values.size, 4)
    assertEquals(values.head, 0.01)
    assertEquals(values.last, 10.0)
    assertEqualsDouble(values(1), 0.1, 1e-15)
    assertEqualsDouble(values(2), 1.0, 1e-15)
  }

  test("lists, single values and seed ranges") {
    assertEquals(grid("count=10,20,40").axes.head.values, Vector(10.0, 20.0, 40.0))
    assertEquals(grid("rate=2.5").axes.head.values, Vector(2.5))
    assertEquals(grid("seed=3..6").axes.head.values, Vector(3.0, 4.0, 5.0, 6.0))
    assertEquals(grid("seed=1,5,9").axes.head.values, Vector(1.0, 5.0, 9.0))
  }

  test("the first axis changes slowest, and the order is fixed") {
    val g = grid("rate=1,2", "count=10,20,30")
    assertEquals(g.size, 6)
    val seen = g.points.map(p => (p.values("rate"), p.values("count")))
    assertEquals(
      seen,
      Vector((1.0, 10.0), (1.0, 20.0), (1.0, 30.0), (2.0, 10.0), (2.0, 20.0), (2.0, 30.0))
    )
    assertEquals(g.points.map(_.index), (0 until 6).toVector)
    assertEquals(grid("rate=1,2", "count=10,20,30").points, g.points)
  }

  test("the seed axis sets the seed and no parameter") {
    val g = grid("rate=0:1:5", "count=10,20", "seed=1..5")
    assertEquals(g.size, 50)
    val p = g.point(7)
    assertEquals(p.seed, Some(3L))
    assertEquals(p.values.keys.toList, List("rate", "count"))
    assert(g.points.forall(_.seed.isDefined))
  }

  test("a grid without axes is one point with default parameters") {
    val g = grid()
    assertEquals(g.size, 1)
    assertEquals(g.point(0).values.size, 0)
    assertEquals(g.point(0).seed, None)
  }

  test("unknown names, ranges and kinds are refused with a message") {
    assert(error("rte=1,2").contains("unknown parameter(s): rte"))
    assert(error("rate=0:20:3").contains("outside"))
    assert(error("count=1.5,2").contains("not an integer"))
    assert(error("enabled=0.5").contains("not a boolean"))
    assert(error("seed=-1,2").contains("seed"))
    assert(error("seed=1.5").contains("seed"))
  }

  test("malformed axes are refused") {
    assert(error("rate").contains("name=values"))
    assert(error("=1,2").contains("name=values"))
    assert(error("rate=").contains("name=values"))
    assert(error("rate=1:2").contains("a:b:n"))
    assert(error("rate=0:1:1").contains("from 2 to"))
    assert(error("rate=0:1:x").contains("from 2 to"))
    assert(error("rate=log:0:1:3").contains("positive"))
    assert(error("rate=log:1:2").contains("log:a:b:n"))
    assert(error("rate=a,b").contains("not a finite number"))
    assert(error("rate=NaN").contains("not a finite number"))
    assert(error("rate=Infinity").contains("not a finite number"))
    assert(error("rate=1,,2").contains("not a finite number"))
    assert(error("seed=5..2").contains("a..b"))
  }

  test("an axis given twice is refused") {
    assert(error("rate=1,2", "rate=3").contains("given twice"))
  }

  test("a huge grid is refused") {
    val message = error("rate=0:10:1000", "seed=0..999")
    assert(message.contains("larger than"), message)
  }
