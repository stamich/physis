package physis.models.fluid

import physis.core.run.Physis

class FluidExportSuite extends munit.FunSuite:
  private val run = Physis.model(Fluid).stepping(Fluid.stepping).build()
  private val spec = FluidExport.spec

  test("every exported field is a slice of the state, once, with a unit and a description") {
    val slices = FluidState.stateExport.layout(run.state).map(_.name)
    assertEquals(spec.fields.map(_.slice), Vector("dye", "vorticity", "speed", "wall"))
    spec.fields.foreach { f =>
      assert(slices.contains(f.slice), s"no slice ${f.slice} in $slices")
      assert(f.units.nonEmpty && f.longName.nonEmpty, f.name)
    }
  }
