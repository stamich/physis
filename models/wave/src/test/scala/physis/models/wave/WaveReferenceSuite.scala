package physis.models.wave

import scala.io.Source

import WaveSupport.*

/** The wave model against the independent numpy implementation in tools/wave_reference.py. */
class WaveReferenceSuite extends munit.FunSuite:

  private def reference: Vector[(Int, Int, Double)] =
    val source =
      Source.fromInputStream(getClass.getResourceAsStream("/wave-box-64x64-200.csv"), "UTF-8")
    try
      source
        .getLines()
        .drop(1)
        .map { line =>
          val c = line.split(',')
          (c(0).toInt, c(1).toInt, c(2).toDouble)
        }
        .toVector
    finally source.close()

  test("64 x 64, 200 steps with a wall, a bump, damping and a source: 1e-10 per cell") {
    val r = box(Edge.Reflective)
    r.runSteps(200)
    val rows = reference
    assertEquals(rows.size, 4096)
    var worst = 0.0
    for (j, i, u) <- rows do worst = math.max(worst, math.abs(r.state.u(i, j) - u))
    assert(worst < 1e-10, s"largest difference $worst")
  }

  test("the reference run is not trivial: the wave has crossed the field and met the wall") {
    val r = box(Edge.Reflective)
    r.runSteps(200)
    assert(r.state.u.max > 0.01 && r.state.u.min < -0.01)
    assertEquals(r.state.u(40, 8), 0.0)
  }

  test("golden digests after 1 000 steps agree with the reference implementation") {
    val reflective = box(Edge.Reflective)
    reflective.runSteps(1000)
    assertEquals(reflective.digest, -802424806129766206L)
    val absorbing = box(Edge.Absorbing)
    absorbing.runSteps(1000)
    assertEquals(absorbing.digest, -5355469018730137378L)
  }
