package physis.models.shallow

import physis.core.Stepping
import physis.fields.Field2D
import ShallowSupport.*

class ShallowSphereSuite extends munit.FunSuite:
  private val dt = Stepping.Fixed(50.0)

  /** A band of the globe of 1° cells, coarse enough for a test. */
  private def globe(
      nx: Int = 72,
      ny: Int = 32,
      nonlinear: Int = 0,
      start: Int = 0,
      amp: Double = 0.0,
      depth: Double = 4000.0,
      latMax: Double = 80.0,
      rotation: Double = 7.292e-5,
      edges: Int = Shallow.Basin
  ): ShallowParams =
    params(
      geometry = 1,
      nx = nx,
      ny = ny,
      nonlinear = nonlinear,
      start = start,
      amp = amp,
      depth = depth,
      latMax = latMax,
      rotation = rotation,
      edges = edges
    )

  private def maxAbs(f: Field2D): Double =
    (for j <- 0 until f.ny; i <- 0 until f.nx yield math.abs(f(i, j))).max

  private def copyOf(f: Field2D): Field2D =
    val c = new Field2D(f.grid)
    c.copyFrom(f)
    c

  private def sphereVolume(r: ShallowRun): Double =
    val g = Shallow.sphereOf(r.params).toOption.get
    (for j <- 0 until g.ny; i <- 0 until g.nx yield r.state.eta(i, j) * g.area(j)).sum

  test("a sea at rest on the sphere stays at rest, bit for bit") {
    val r = run(globe(nonlinear = 1), dt)
    val before = r.digest
    r.runSteps(50)
    assertEquals(r.digest, before)
  }

  test("a hump spreads and the volume is conserved to rounding") {
    for nonlinear <- Seq(0, 1) do
      val r = run(globe(nonlinear = nonlinear), dt)
      hump(r.state, 20.0, 12.0, 3.0, 1.0)
      val v0 = sphereVolume(r)
      r.runSteps(200)
      assert(math.abs(sphereVolume(r) - v0) < 1e-9 * math.abs(v0), s"nonlinear $nonlinear")
      assert(maxAbs(r.state.eta) < 1.0, "the hump has spread")
  }

  test("the rotation of the sphere matters: the Coriolis force bends the flow") {
    def digest(rotation: Double): Long =
      val r = run(globe(rotation = rotation), dt)
      hump(r.state, 20.0, 12.0, 3.0, 1.0)
      r.runSteps(100)
      r.digest
    assertNotEquals(digest(7.292e-5), digest(0.0))
  }

  test("the plane is unchanged: the new parameters do not touch it") {
    val a = run(params(f0 = 1e-4, nonlinear = 1), Stepping.Fixed(60.0))
    hump(a.state, 12.0, 10.0, 3.0, 0.5)
    a.runSteps(100)
    val b =
      run(params(f0 = 1e-4, nonlinear = 1, radius = 1e7, rotation = 1e-4), Stepping.Fixed(60.0))
    hump(b.state, 12.0, 10.0, 3.0, 0.5)
    b.runSteps(100)
    assertEquals(a.digest, b.digest)
  }

  test("the sphere refuses what belongs to the plane") {
    assert(Shallow.check(globe(), dt).isRight)
    assert(Shallow.check(globe(edges = Shallow.OpenSea), dt).isLeft)
    assert(Shallow.check(globe(start = 3), dt).isLeft)
    assert(Shallow.check(globe().copy(seabed = 3), dt).isLeft)
    assert(Shallow.check(params(start = 5), dt).isLeft)
  }

  test("the step is refused where the waves would cross a cell of the narrow rows too fast") {
    assert(Shallow.check(globe(latMax = 85.0), Stepping.Fixed(50.0)).isRight)
    assert(Shallow.check(globe(latMax = 85.0), Stepping.Fixed(500.0)).isLeft)
  }

  test("a Kelvin-like wave runs round the globe in the time the speed gives") {
    // a gravity wave on the equator: c = sqrt(g h), the way round is 2 pi R
    val p = globe(nx = 120, ny = 20, latMax = 10.0, rotation = 0.0)
    val r = run(p, dt)
    hump(r.state, 60.0, 10.0, 3.0, 0.1)
    val c = math.sqrt(p.gravity * p.depth)
    val steps = (2 * math.Pi * p.radius / c / 50.0).round.toInt
    r.runSteps(steps)
    val g = Shallow.sphereOf(p).toOption.get
    val best = (0 until g.nx).maxBy(i => r.state.eta(i, 10))
    assert(math.abs(best - 60) <= 3, s"the crest is back near column 60, not $best")
  }

  test("Williamson 2: a steady zonal flow stays steady") {
    val p = globe(nx = 72, ny = 32, nonlinear = 1, start = 5, amp = 38.61, depth = 2998.6)
    val r = run(p, dt)
    val u0 = copyOf(r.state.u)
    val e0 = copyOf(r.state.eta)
    r.runSteps(100)
    val g = Shallow.sphereOf(p).toOption.get
    var worst = 0.0
    for j <- 0 until g.ny; i <- 0 until g.nx do
      worst = math.max(worst, math.abs(r.state.eta(i, j) - e0(i, j)))
    assert(worst < 5.0, s"the height moved by $worst m")
    assert(maxAbs(r.state.u) > 0.9 * maxAbs(u0))
  }
