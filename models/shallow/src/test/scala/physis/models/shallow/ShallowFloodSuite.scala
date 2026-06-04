package physis.models.shallow

import physis.core.Stepping
import physis.core.run.Intervention
import ShallowSupport.*

class ShallowFloodSuite extends munit.FunSuite:
  private val small = Stepping.Fixed(5.0)

  /** A sea 10 m deep whose floor rises from the middle of the grid and comes out of the water. */
  private def beachRun(nx: Int = 40, ny: Int = 12, rise: Double = 0.1): ShallowRun =
    val r = run(
      params(depth = 10.0, dx = 100.0, nonlinear = 1, flooding = 1, nx = nx, ny = ny),
      small
    )
    val s = r.state
    for j <- 0 until ny; i <- 0 until nx do
      s.shape(i, j) = if i < nx / 2 then 1.0 else 1.0 - rise * (i - nx / 2 + 1)
    s.settle()
    r

  test("on a flat sea flooding changes nothing: the digest is the same, bit for bit") {
    for (edges, f0, sponge) <-
        Seq((Shallow.Basin, 0.0, 0), (Shallow.OpenSea, 1e-4, 6), (Shallow.Channel, 1e-4, 0))
    do
      def digest(flooding: Int): Long =
        val r = run(
          params(nonlinear = 1, flooding = flooding, edges = edges, f0 = f0, sponge = sponge)
        )
        hump(r.state, 12.0, 10.0, 3.0, 0.5)
        r.runSteps(120)
        r.digest
      assertEquals(digest(1), digest(0), s"edges $edges")
  }

  test("flooding needs the nonlinear equations") {
    val p = params(flooding = 1, nonlinear = 0)
    assert(Shallow.check(p, small).isLeft)
    assert(Shallow.check(p.copy(nonlinear = 1), small).isRight)
  }

  test("a lake at rest against a beach stays at rest, exactly") {
    val r = beachRun()
    val s = r.state
    val before = s.eta.data.clone()
    r.runSteps(300)
    assertEquals(s.eta.data.toSeq, before.toSeq)
    assertEquals(s.u.data.map(math.abs).max, 0.0)
    assertEquals(s.v.data.map(math.abs).max, 0.0)
    assertEquals(s.volume, 0.0)
    assertEquals(s.energy, 0.0)
  }

  test("the dry cells keep their surface at the bed, which is above the sea") {
    val s = beachRun().state
    var dry = 0
    for j <- 0 until 12; i <- 0 until 40 do
      val bed = -10.0 * s.shape(i, j)
      if bed > 0.0 then
        dry += 1
        assertEqualsDouble(s.eta(i, j), bed, 1e-12)
      else assertEquals(s.eta(i, j), 0.0)
    assert(dry > 0, "the beach should come out of the water")
  }

  test("a wave runs up the beach, the depth never goes negative and the volume is exact") {
    val r = beachRun(nx = 60, ny = 8, rise = 0.05)
    val s = r.state
    ridge(s, 10.0, 3.0, 2.0)
    s.settle()
    val volume = s.volume
    var highest = 0.0
    for _ <- 1 to 60 do
      r.runSteps(10)
      for j <- 0 until 8; i <- 0 until 60 do
        val bed = -10.0 * s.shape(i, j)
        assert(s.eta(i, j) >= bed - 1e-12, s"negative depth at ($i,$j) after ${r.stepCount} steps")
        if bed > 0.0 then highest = math.max(highest, s.eta(i, j) - bed)
      assertEqualsDouble(s.volume, volume, 1e-9 * s.mac.nx * s.mac.ny)
    assert(
      highest > 0.05,
      f"the water never reached the land (highest depth over land $highest%.3f m)"
    )
  }

  test("painting cannot dig the surface below the floor") {
    val r = beachRun()
    r.runSteps(5)
    assertEquals(r.intervene(Intervention.Pulse("height", 30.0, 6.0, 4.0, -8.0)), Right(()))
    r.runSteps(1)
    val s = r.state
    for j <- 0 until 12; i <- 0 until 40 do
      assert(s.eta(i, j) >= -10.0 * s.shape(i, j) - 1e-12, s"below the floor at ($i,$j)")
  }

  test("a step allocates nothing, with the water on the beach") {
    assume(physis.verify.Allocation.supported)
    val r = beachRun()
    ridge(r.state, 10.0, 3.0, 0.5)
    r.state.settle()
    r.runSteps(20)
    val bytes = physis.verify.Allocation.perStep(100)(r.step())
    assert(bytes < 3000L, s"$bytes bytes over 100 steps")
  }
