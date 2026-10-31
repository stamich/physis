package physis.models.shallow

import physis.core.Stepping
import ShallowSupport.*

class ShallowBeachSuite extends munit.FunSuite:
  /** A small sea with a 1 m deep surf zone: cells of 0.25 m and a step of 0.05 s. */
  private val tank = Stepping.Fixed(0.05)

  private def beach(
      seabed: Int,
      start: Int = 0,
      amp: Double = 0.0,
      slope: Double = 0.05,
      nx: Int = 400,
      ny: Int = 16
  ) = run(
    params(
      depth = 1.0,
      dx = 0.25,
      nonlinear = 1,
      flooding = 1,
      seabed = seabed,
      start = start,
      amp = amp,
      slope = slope,
      nx = nx,
      ny = ny,
      dryDepth = 1e-4
    ),
    tank
  ).state

  test("a plane beach is flat out to the toe and then rises by the slope") {
    val s = beach(3, slope = 0.1)
    val toe = 200.0
    for i <- 0 until 400 do
      val bed = -s.shape(i, 3)
      val x = i + 0.5
      if x <= toe then assertEquals(s.shape(i, 3), 1.0)
      else assertEqualsDouble(bed, -1.0 + 0.1 * (x - toe) * 0.25, 1e-12)
    // the shore is where the bed comes out of the water: 1 m at 0.1 m/m of 0.25 m cells is 40 cells
    val shore = (0 until 400).find(i => s.shape(i, 3) < 0.0).get
    assertEquals(shore, 240)
  }

  test("the dry part of the beach holds its surface at the bed, the sea at rest") {
    val s = beach(3, slope = 0.1)
    for i <- 0 until 400 do
      val bed = -s.shape(i, 5)
      assertEqualsDouble(s.eta(i, 5), math.max(0.0, bed), 1e-12)
  }

  test("the sea floors 3 and 4 need flooding") {
    val p = params(seabed = 3, nonlinear = 1, flooding = 0)
    assert(Shallow.check(p, Stepping.Fixed(5.0)).isLeft)
    assert(Shallow.check(p.copy(flooding = 1), Stepping.Fixed(5.0)).isRight)
    assert(Shallow.check(p.copy(seabed = 4), Stepping.Fixed(5.0)).isLeft)
  }

  test(
    "a bay is a dip in the beach: deeper in the middle rows, the same at the toe and at the sides"
  ) {
    val s = beach(4, slope = 0.1)
    val plain = beach(3, slope = 0.1)
    for j <- 0 until 16 do
      assertEquals(s.shape(100, j), 1.0) // before the toe
      assertEquals(s.shape(199, j), plain.shape(199, j)) // the last cell before the toe
    assert(s.shape(260, 8) > plain.shape(260, 8) + 0.2, "the bay is deeper in the middle")
    assertEqualsDouble(s.shape(260, 0), plain.shape(260, 0), 1e-2) // far from the bay
    // the shore of the bay is farther inland than the shore at the sides
    def shore(j: Int) = (0 until 400).find(i => s.shape(i, j) < 0.0).get
    assert(shore(8) > shore(0) + 5, s"shores at ${shore(8)} and ${shore(0)}")
  }

  test("a solitary wave has the height, the volume and the speed of water of the formula") {
    val amp = 0.0185
    val s = beach(0, start = 3, amp = amp, nx = 400)
    val p = params(depth = 1.0, dx = 0.25, start = 3, amp = amp, nx = 400)
    val w = Shallow.waveWidth(p)
    assertEqualsDouble(w, math.sqrt(4.0 / (3.0 * amp)), 1e-12)
    val peak = (0 until 400).maxBy(i => s.eta(i, 4))
    assertEqualsDouble((peak + 0.5) * 0.25, 25.0, 0.25)
    assertEqualsDouble(s.eta(peak, 4), amp, 1e-3 * amp + 0.01 * amp)
    val volume = (0 until 400).map(i => s.eta(i, 4) * 0.25).sum
    assertEqualsDouble(volume, 2.0 * amp * w, 0.01 * 2.0 * amp * w)
    // the water moves right with c η / (h + η)
    val c = math.sqrt(9.81 * (1.0 + amp))
    val i = peak
    val eta = amp * 1.0 / math.pow(math.cosh((i * 0.25 - 25.0) / w), 2)
    assertEqualsDouble(s.u(i, 4), c * eta / (1.0 + eta), 1e-12)
    assert(s.u(i, 4) > 0.0)
    // every row is the same: the wave is two-dimensional
    assertEquals(s.eta(peak, 0), s.eta(peak, 15))
  }

  test("the width of a wave is never under five cells") {
    val p = params(depth = 10.0, dx = 100.0, amp = 10.0)
    assertEquals(Shallow.waveWidth(p), 500.0)
    val narrow = params(depth = 1.0, dx = 0.25, amp = 0.0185)
    assertEqualsDouble(Shallow.waveWidth(narrow), math.sqrt(4.0 / (3.0 * 0.0185)), 1e-12)
  }

  test("an N-wave has a hump behind a dip, and no net volume") {
    val s = beach(0, start = 4, amp = 0.05, nx = 400)
    val p = params(depth = 1.0, dx = 0.25, start = 4, amp = 0.05, nx = 400)
    val w = Shallow.waveWidth(p)
    val hump = (0 until 400).maxBy(i => s.eta(i, 2))
    val dip = (0 until 400).minBy(i => s.eta(i, 2))
    assertEqualsDouble((hump + 0.5) * 0.25, 25.0, 0.3)
    assertEqualsDouble((dip + 0.5) * 0.25, 25.0 + 3.0 * w, 0.3)
    assertEqualsDouble(s.eta(hump, 2), 0.05, 1e-3)
    assertEqualsDouble(s.eta(dip, 2), -0.05, 1e-3)
    val volume = (0 until 400).map(i => s.eta(i, 2) * 0.25).sum
    assertEqualsDouble(volume, 0.0, 0.005) // what the wall cuts off the tail of the hump
  }
