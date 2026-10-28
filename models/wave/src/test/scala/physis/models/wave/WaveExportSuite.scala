package physis.models.wave

import physis.core.run.Physis

class WaveExportSuite extends munit.FunSuite:
  private val run = Physis.model(Wave).stepping(Wave.stepping).build()
  private val spec = WaveExport.spec

  test("every exported field is a slice of the state, once, with a unit and a description") {
    val slices = WaveState.stateExport.layout(run.state).map(_.name)
    assertEquals(spec.fields.map(_.slice), Vector("u", "wall"))
    spec.fields.foreach { f =>
      assert(slices.contains(f.slice), s"no slice ${f.slice} in $slices")
      assert(f.units.nonEmpty && f.longName.nonEmpty, f.name)
    }
  }
