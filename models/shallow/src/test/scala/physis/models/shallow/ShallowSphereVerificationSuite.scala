package physis.models.shallow

import physis.core.Stepping
import ShallowSupport.*

/**
 * The sphere against what is known: the speed of a gravity wave, Williamson et al.'s tests 2 and 6,
 * and the numbers of `tools/shallow_sphere_reference.py`, an independent implementation of the
 * scheme in numpy. Tolerances are set from that script's output (quoted in each test) with room
 * for the difference in rounding.
 */
class ShallowSphereVerificationSuite extends munit.FunSuite:
  private val dt = Stepping.Fixed(50.0)
  private val G = 9.81
  private val Radius = 6.371e6
  private val Omega = 7.292e-5
  private val Width = math.toRadians(10)

  private def band(
      start: Int = 0,
      amp: Double = 0.0,
      depth: Double = 4000.0,
      nonlinear: Int = 1
  ): ShallowParams =
    params(
      geometry = 1,
      nx = 180,
      ny = 80,
      latMax = 80.0,
      depth = depth,
      start = start,
      amp = amp,
      nonlinear = nonlinear
    )

  private def areaOf(p: ShallowParams, j: Int): Double =
    Shallow.sphereOf(p).toOption.get.area(j)

  private def volume(r: ShallowRun): Double =
    val p = r.params
    (for j <- 0 until p.ny; i <- 0 until p.nx yield r.state.eta(i, j) * areaOf(p, j)).sum

  test("Kelvin: a gravity wave on the equator travels at sqrt(g h); numpy gets 1.0069") {
    val p = band(nonlinear = 0)
    val r = run(p, dt)
    val g = Shallow.sphereOf(p).toOption.get
    for j <- 0 until p.ny; i <- 0 until p.nx do
      val lam = (i + 0.5) * g.dlam
      val lat = g.latC(j)
      val dl = lam - math.Pi
      r.state.eta(i, j) = math.exp(-(dl * dl + 4.0 * lat * lat) / (2 * Width * Width))
    val c = math.sqrt(G * 4000.0)
    val tEnd = 0.4 * 2 * math.Pi * Radius / c
    r.runSteps((tEnd / 50.0).round)
    val row = p.ny / 2
    val east = (p.nx / 2 + 1 until p.nx).maxBy(i => r.state.eta(i, row - 1) + r.state.eta(i, row))
    val dist = (east + 0.5 - p.nx / 2) * g.dlam * Radius
    val ratio = dist / (r.time) / c
    assert(ratio > 0.98 && ratio < 1.03, f"the crest travelled at $ratio%.4f of sqrt(g h)")
  }

  test("a wave goes round the globe and the energy and volume hold; numpy: 2.6e-4, 3e-16") {
    val p = band(nonlinear = 0)
    val r = run(p, dt)
    val g = Shallow.sphereOf(p).toOption.get
    for j <- 0 until p.ny; i <- 0 until p.nx do
      val lam = (i + 0.5) * g.dlam
      val lat = g.latC(j)
      val dl = lam - math.Pi
      r.state.eta(i, j) = math.exp(-(dl * dl + 4.0 * lat * lat) / (2 * Width * Width))
    val e0 = r.state.energy
    val v0 = volume(r)
    val c = math.sqrt(G * 4000.0)
    r.runSteps((2 * math.Pi * Radius / c / 50.0).round)
    assert(math.abs(r.state.energy / e0 - 1.0) < 2e-3, s"energy ${r.state.energy / e0}")
    assert(math.abs(volume(r) - v0) < 1e-12 * math.abs(v0))
    val row = p.ny / 2
    val best = (0 until p.nx).maxBy(i => r.state.eta(i, row - 1) + r.state.eta(i, row))
    assert(math.abs(best - 90) <= 8, s"the crest is back at column $best, not near 90")
  }

  test("Williamson 2: a steady zonal flow keeps its height; numpy: error 6.9e-6 after a day") {
    val h0 = 2.94e4 / G
    val u0 = 2.0 * math.Pi * Radius / (12 * 86400.0)
    val p = band(start = 5, amp = u0, depth = h0)
    val r = run(p, dt)
    val e0 = Array.tabulate(p.ny, p.nx)((j, i) => r.state.eta(i, j))
    val v0 = volume(r)
    r.runSteps(86400 / 50)
    var num = 0.0
    var den = 0.0
    for j <- 0 until p.ny; i <- 0 until p.nx do
      val a = areaOf(p, j)
      val d = r.state.eta(i, j) - e0(j)(i)
      num += a * d * d
      den += a * (e0(j)(i) + h0) * (e0(j)(i) + h0)
    val err = math.sqrt(num / den)
    assert(err < 5e-5, f"the l2 error of the height is $err%.2e")
    assert(math.abs(volume(r) - v0) < 1e-12 * math.abs(h0 * Radius * Radius))
    var vmax = 0.0
    for j <- 1 until p.ny; i <- 0 until p.nx do vmax = math.max(vmax, math.abs(r.state.v(i, j)))
    assert(vmax < 0.05, s"the flow turned: |v| reached $vmax m/s")
  }

  test("Williamson 6: the Rossby-Haurwitz wave moves east at about 0.82 of the barotropic theory") {
    val p = band(start = 6, amp = 7.848, depth = 8000.0)
    val r = run(p, dt)
    val g = Shallow.sphereOf(p).toOption.get
    val row = (0 until p.ny).minBy(j => math.abs(g.latC(j) - math.toRadians(45)))
    def phase(): Double =
      var c = 0.0
      var s = 0.0
      for i <- 0 until p.nx do
        val lam = (i + 0.5) * g.dlam
        c += r.state.eta(i, row) * math.cos(4 * lam)
        s += r.state.eta(i, row) * math.sin(4 * lam)
      math.atan2(s, c)
    val p0 = phase()
    val w = 7.848e-6
    val nu = (4 * 7 * w - 2 * Omega) / (5.0 * 6.0)
    r.runSteps(3 * 86400 / 50)
    var shift = (phase() - p0) / 4.0
    shift = (shift + math.Pi / 4) % (math.Pi / 2)
    if shift < 0 then shift += math.Pi / 2
    shift -= math.Pi / 4
    val ratio = shift / (nu * 3 * 86400.0)
    assert(ratio > 0.76 && ratio < 0.88, f"the wave moved at $ratio%.3f of the theory")
  }
