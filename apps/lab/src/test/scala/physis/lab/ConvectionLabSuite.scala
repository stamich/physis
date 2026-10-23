package physis.lab

import physis.core.run.{Intervention, Physis}
import physis.models.convection.Convection

class ConvectionLabSuite extends munit.FunSuite:
  test("convection offers heat, cold and walls whose strokes the model accepts") {
    assertEquals(
      Catalog.convection.tools.map(_.spec.name),
      Vector("Heat spot", "Cool spot", "Wall", "Erase wall")
    )
    val run = Physis
      .model(Convection)
      .stepping(Convection.stepping)
      .overrides(Map("nx" -> 64.0, "ny" -> 32.0))
      .build()
    for tool <- Catalog.convection.tools; secondary <- Seq(false, true) do
      val iv = tool.make(physis.view.Dab(10.0, 12.0), 5.0, 0.5, secondary)
      assert(run.intervene(iv).isRight, s"${tool.spec.name}: $iv")
      assert(iv.isInstanceOf[Intervention.Brush])
  }

  test("a cool spot paints a negative deviation and a heat spot a positive one") {
    val dab = physis.view.Dab(10.0, 12.0)
    def value(name: String): Double =
      Catalog.convection.tools.find(_.spec.name == name).get.make(dab, 5.0, 0.5, false) match
        case Intervention.Brush(_, _, _, v) => v
        case other => fail(s"not a brush: $other")
    assertEquals(value("Heat spot"), 0.5)
    assertEquals(value("Cool spot"), -0.5)
  }

  test("strokes wrap with periodic sides only") {
    assert(Catalog.convection.wraps(Map("sides" -> 0.0)))
    assert(!Catalog.convection.wraps(Map("sides" -> 1.0)))
  }

  test("every preset is offered, and the one with painted heat restarts with it") {
    assertEquals(Catalog.convection.presets.size, 5)
    val preset = Catalog.convection.presets.find(_.name == "Painted heat").get
    Catalog.convection.presetCommand(preset, Map.empty) match
      case physis.core.live.Command.Restart(values, _, Some(paint)) =>
        assertEquals(values("sides"), 1.0)
        assert(paint.nonEmpty)
      case other => fail(s"expected a restart, got $other")
  }

  test("the convection session can save images and animations, and runs") {
    val session = Catalog.convection.open(seed = 2L, startPaused = true)
    assert(session.hasImage)
    assertEquals(session.animation.every, 100L)
  }
