package physis.models.shallow

import physis.core.run.Physis

class ShallowExportSuite extends munit.FunSuite:
  private val run = Physis.model(Shallow).stepping(Shallow.stepping).build()
  private val spec = ShallowExport.spec(Shallow.params.defaults)

  test("every exported field is a slice of the state, once, with a unit and a description") {
    val slices = ShallowState.stateExport.layout(run.state).map(_.name)
    assertEquals(
      spec.fields.map(_.slice),
      Vector("height", "speed", "vorticity", "depth", "land", "water", "dry")
    )
    spec.fields.foreach { f =>
      assert(slices.contains(f.slice), s"no slice ${f.slice} in $slices")
      assert(f.units.nonEmpty && f.longName.nonEmpty, f.name)
    }
  }

  test("the sea is in metres and seconds, with y running south, and a cell has the size of dx") {
    assertEquals(spec.lengthUnits, "m")
    assertEquals(spec.timeUnits, "s")
    assert(spec.yDown)
    assertEquals(spec.dx, 20000.0)
    assertEquals(ShallowExport.spec(Shallow.params.defaults.copy(dx = 5000.0)).dx, 5000.0)
    assertEquals(spec.fields.head.standardName, Some("sea_surface_height_above_mean_sea_level"))
  }
