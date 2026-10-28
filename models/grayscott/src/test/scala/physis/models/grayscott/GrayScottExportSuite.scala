package physis.models.grayscott

import physis.core.run.Physis

class GrayScottExportSuite extends munit.FunSuite:
  private val run = Physis.model(GrayScott).stepping(GrayScott.stepping).build()
  private val spec = GrayScottExport.spec

  test("every exported field is a slice of the state, once, with a unit and a description") {
    val slices = GrayScottState.stateExport.layout(run.state).map(_.name)
    assertEquals(spec.fields.map(_.slice), Vector("u", "v"))
    spec.fields.foreach { f =>
      assert(slices.contains(f.slice), s"no slice ${f.slice} in $slices")
      assert(f.units.nonEmpty && f.longName.nonEmpty, f.name)
    }
  }
