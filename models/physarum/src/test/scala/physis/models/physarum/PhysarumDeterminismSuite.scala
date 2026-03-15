package physis.models.physarum

import physis.core.run.Physis

class PhysarumDeterminismSuite extends munit.FunSuite:

  test("golden digest: 500 seeded agents on 64 x 64 after 300 steps are the same everywhere") {
    val run = Physis
      .model(Physarum)
      .stepping(Physarum.stepping)
      .overrides(Map("n" -> 500.0, "nx" -> 64.0, "ny" -> 64.0))
      .seed(7L)
      .build()
    run.runSteps(300)
    assertEquals(run.digest, -8725111938563089929L)
  }
