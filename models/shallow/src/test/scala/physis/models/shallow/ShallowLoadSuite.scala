package physis.models.shallow

import physis.core.Stepping
import physis.core.run.{Intervention, Raster, Rasters}
import ShallowSupport.*

class ShallowLoadSuite extends munit.FunSuite:
  // from 10 m under the sea at the west to 5 m above it at the east
  private val digest = 0x5ea0f100L
  Rasters.register(digest, new Raster(2, 1, Array(-10.0, 5.0)))
  private val load = Intervention.Load("seafloor", "ramp.nc", digest)

  private val wet = params(depth = 10.0, dx = 100.0, nonlinear = 1, flooding = 1, nx = 40, ny = 12)

  test("a sea floor from a file sets the bed and the water stays at rest") {
    val r = run(wet, Stepping.Fixed(5.0))
    val s = r.state
    assertEquals(Shallow.intervene(s, wet, load), Right(()))
    assertEqualsDouble(s.shape(0, 0), 1.0, 1e-12)
    assertEqualsDouble(s.shape(39, 0), -0.5, 1e-12)
    assertEquals(s.eta(0, 0), 0.0)
    assertEqualsDouble(s.eta(39, 5), 5.0, 1e-12) // dry land of the shore sits at its floor
    val before = s.eta.data.clone()
    r.runSteps(100)
    assertEquals(s.eta.data.toSeq, before.toSeq)
    assertEquals(s.volume, 0.0)
  }

  test("it is refused without flooding, for a floor nobody read and for another field") {
    val plain = params(nx = 40, ny = 12)
    assert(Shallow.intervene(run(plain).state, plain, load).isLeft)
    assert(Shallow.accepts(Intervention.Load("seafloor", "x.nc", 42L)).isLeft)
    assert(Shallow.accepts(Intervention.Load("height", "ramp.nc", digest)).isLeft)
    assertEquals(Shallow.accepts(load), Right(()))
  }

  test("on the sphere the floor is read by longitude and latitude") {
    // a global raster of 72 columns like the grid: land (+300 m) from 0 to 90 east, else 4000 m deep
    val vals = Array.tabulate(72 * 2)(k => if k % 72 >= 36 && k % 72 < 54 then 300.0 else -4000.0)
    val globeDigest = 0x5ea0f101L
    Rasters.register(
      globeDigest,
      new Raster(72, 2, vals, Some(Raster.Extent(-180.0, 180.0, -90.0, 90.0)))
    )
    val p = params(
      geometry = 1,
      nx = 72,
      ny = 32,
      depth = 4000.0,
      nonlinear = 1,
      flooding = 1,
      latMax = 80.0
    )
    val r = run(p, Stepping.Fixed(50.0))
    val s = r.state
    val done = Shallow.intervene(s, p, Intervention.Load("seafloor", "g.nc", globeDigest))
    assertEquals(done, Right(()))
    assertEqualsDouble(s.shape(40, 8), -300.0 / 4000.0, 1e-9)
    assertEqualsDouble(s.shape(10, 8), 1.0, 1e-9)
    assertEqualsDouble(s.shape(60, 20), 1.0, 1e-9)
  }

  test("on the sphere a floor without coordinates is refused") {
    val p = params(geometry = 1, nx = 72, ny = 32, nonlinear = 1, flooding = 1)
    val r = run(p, Stepping.Fixed(50.0))
    val refused = Shallow.intervene(r.state, p, load)
    assert(refused.left.exists(_.contains("lat")), refused.toString)
  }
