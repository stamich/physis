package physis.models.convection

import physis.core.run.Physis

class ConvectionExportSuite extends munit.FunSuite:
  private val run = Physis.model(Convection).stepping(Convection.stepping).build()
  private val spec = ConvectionExport.spec(Convection.params.defaults)

  test("every exported field is a slice of the state, once, with a unit and a description") {
    val slices = ConvectionState.stateExport.layout(run.state).map(_.name)
    assertEquals(
      spec.fields.map(_.slice),
      Vector("temperature", "deviation", "vorticity", "speed", "wall")
    )
    spec.fields.foreach { f =>
      assert(slices.contains(f.slice), s"no slice ${f.slice} in $slices")
      assert(f.units.nonEmpty && f.longName.nonEmpty, f.name)
    }
  }

  test("a cell is one layer depth divided by the number of rows") {
    assertEqualsDouble(spec.dx, 1.0 / Convection.params.defaults.ny, 1e-15)
    assertEquals(ConvectionExport.spec(Convection.params.defaults.copy(ny = 20)).dx, 0.05)
  }
