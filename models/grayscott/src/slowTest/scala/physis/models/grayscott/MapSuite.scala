package physis.models.grayscott

import physis.core.sweep.{ParamGrid, Sweep, SweepSetup}

/**
 * A sweep over a 5 x 5 patch of the (F, k) plane (Pearson's map), classified by what is left
 * of v after 8 000 steps on 128 x 128 without noise. The expected classes were taken from the
 * independent simulation (`python tools/gray_scott_reference.py map`), which gives, as
 * coverage of v > 0.2 / largest v:
 *
 * {{{
 *            k = 0.050      0.055        0.060        0.065        0.070
 * F = 0.02   0.244/0.421    0.141/0.354  0/0          0/0          0/0
 * F = 0.03   1.000/0.259    0.639/0.285  0.306/0.358  0/0          0/0
 * F = 0.04   1.000/0.319    1.000/0.276  0.530/0.340  0.010/0.403  0/0
 * F = 0.05   1.000/0.362    1.000/0.320  0.982/0.320  0.011/0.420  0/0
 * F = 0.06   1.000/0.393    1.000/0.351  1.000/0.300  0.005/0.410  0/0
 * }}}
 *
 * The model repeats the reference's arithmetic, so nearly every point should agree; the check
 * allows five to differ, because points near a class border (0.982 against 0.95) are fragile.
 */
class MapSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** E extinct, S a few spots, P a pattern, U v everywhere. Rows: F; columns: k. */
  private val expected = Vector("PPEEE", "UPPEE", "UUPSE", "UUUSE", "UUUSE")

  private def classify(coverage: Double, vMax: Double): Char =
    if vMax < 1e-3 then 'E'
    else if coverage > 0.95 then 'U'
    else if coverage < 0.05 then 'S'
    else 'P'

  test("at least 20 of 25 points of the F-k map are in the class the reference gives") {
    val grid = ParamGrid
      .parse(Seq("F=0.02:0.06:5", "k=0.05:0.07:5"), GrayScott.params)
      .fold(e => fail(e), identity)
    assertEquals(grid.size, 25)
    val setup = SweepSetup[GrayScottParams, GrayScottState](
      GrayScott,
      _ => GrayScott.probes,
      GrayScott.stepping,
      until = 8000.0,
      sampleEvery = 8000.0,
      params = Map("nx" -> 128.0, "ny" -> 128.0, "noise" -> 0.0),
      seed = 1L
    )
    val results = Sweep.run(setup, grid, Sweep.defaultThreads) { (run, _) =>
      (run.state.coverage(0.2), run.state.v.max)
    }
    assertEquals(results.size, 25)
    val classes = results.map { r =>
      r.outcome.fold(
        e => fail(s"point ${r.point.index}: $e"),
        o => classify(o.extra._1, o.extra._2)
      )
    }
    val rows = classes.grouped(5).map(_.mkString).toVector
    val agree = rows.zip(expected).map((a, b) => a.zip(b).count(_ == _)).sum
    assert(
      agree >= 20,
      s"$agree of 25 agree; got ${rows.mkString(" ")}, expected ${expected.mkString(" ")}"
    )
  }
