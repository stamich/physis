package physis.models.physarum

import physis.core.run.Physis

class PhysarumExportSuite extends munit.FunSuite:
  private val run = Physis.model(Physarum).stepping(Physarum.stepping).build()
  private val spec = PhysarumExport.spec

  test("every exported field is a slice of the state, once, with a unit and a description") {
    val slices = PhysarumState.stateExport.layout(run.state).map(_.name)
    assertEquals(spec.fields.map(_.slice), Vector("trail", "food"))
    spec.fields.foreach { f =>
      assert(slices.contains(f.slice), s"no slice ${f.slice} in $slices")
      assert(f.units.nonEmpty && f.longName.nonEmpty, f.name)
    }
  }
