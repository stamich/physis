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
