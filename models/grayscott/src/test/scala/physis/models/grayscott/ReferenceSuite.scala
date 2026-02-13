package physis.models.grayscott

import scala.io.Source

import physis.core.run.Physis

/** Gray-Scott against the independent numpy implementation in tools/gray_scott_reference.py. */
class ReferenceSuite extends munit.FunSuite:

  private def run64(steps: Int) =
    val run = Physis
      .model(GrayScott)
      .stepping(GrayScott.stepping)
      .overrides(Map("nx" -> 64.0, "ny" -> 64.0, "noise" -> 0.0))
      .build()
    run.runSteps(steps.toLong)
    run

  /** Rows (j, i, u, v) of gray-scott-64x64-100.csv. */
  private def reference: Vector[(Int, Int, Double, Double)] =
    val source =
      Source.fromInputStream(getClass.getResourceAsStream("/gray-scott-64x64-100.csv"), "UTF-8")
    try
      source
        .getLines()
        .drop(1)
        .map { line =>
          val c = line.split(',')
          (c(0).toInt, c(1).toInt, c(2).toDouble, c(3).toDouble)
        }
        .toVector
    finally source.close()

  test("64 x 64, 100 steps: u and v agree with the numpy reference to 1e-10") {
    val state = run64(100).state
    val rows = reference
    assertEquals(rows.size, 4096)
    var worst = 0.0
    for (j, i, u, v) <- rows do
      worst = math.max(worst, math.max(math.abs(state.u(i, j) - u), math.abs(state.v(i, j) - v)))
    assert(worst < 1e-10, s"largest difference $worst")
  }

  test("the reference run is not trivial: the seed has spread") {
    val state = run64(100).state
    assert(state.v.max > 0.3 && state.v.max < 0.45, s"v_max ${state.v.max}")
    assert(state.u.min > 0.2 && state.u.min < 0.3, s"u_min ${state.u.min}")
  }

  test("golden digest after 1 000 steps (64 x 64), from the reference implementation") {
    // python tools/gray_scott_reference.py digest. Bit-exact: same operations in the same order.
    assertEquals(run64(1000).digest, 216803682042089304L)
  }
