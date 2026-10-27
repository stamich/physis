package physis.lab

import physis.core.run.{Intervention, Physis}
import physis.models.shallow.Shallow

class ShallowLabSuite extends munit.FunSuite:
  private val dab = physis.view.Dab(10.0, 12.0)

  private def tool(name: String) =
    Catalog.shallow.tools.find(_.spec.name == name).getOrElse(fail(s"no tool $name"))

  test("shallow water offers uplift, drop and land whose strokes the model accepts") {
    assertEquals(
      Catalog.shallow.tools.map(_.spec.name),
      Vector("Uplift", "Drop", "Land", "Erase land")
    )
    val run = Physis
      .model(Shallow)
      .stepping(Shallow.stepping)
      .overrides(Map("nx" -> 64.0, "ny" -> 32.0))
      .build()
    for t <- Catalog.shallow.tools; secondary <- Seq(false, true) do
      val iv = t.make(dab, 5.0, 0.5, secondary)
      assert(run.intervene(iv).isRight, s"${t.spec.name}: $iv")
  }

  test("an uplift raises the surface, a drop lowers it, and the right button reverses both") {
    def amplitude(name: String, secondary: Boolean): Double =
      tool(name).make(dab, 5.0, 0.5, secondary) match
        case Intervention.Pulse("height", _, _, _, a) => a
        case other => fail(s"not a pulse of height: $other")
    assertEquals(amplitude("Uplift", false), 0.5)
    assertEquals(amplitude("Uplift", true), -0.5)
    assertEquals(amplitude("Drop", false), -0.5)
    assertEquals(amplitude("Drop", true), 0.5)
  }

  test("land is painted by the left button and taken away by the right one") {
    tool("Land").make(dab, 3.0, 0.0, false) match
      case Intervention.Brush("land", _, op, v) =>
        assertEquals(op, physis.core.run.Op.Assign)
        assertEquals(v, 1.0)
      case other => fail(s"not a brush of land: $other")
    tool("Land").make(dab, 3.0, 0.0, true) match
      case Intervention.Brush("land", _, op, _) => assertEquals(op, physis.core.run.Op.Erase)
      case other => fail(s"not a brush of land: $other")
  }

  test("strokes wrap with a channel only") {
    assert(Catalog.shallow.wraps(Map("edges" -> 0.0)))
    assert(!Catalog.shallow.wraps(Map("edges" -> 1.0)))
    assert(!Catalog.shallow.wraps(Map("edges" -> 2.0)))
  }

  test("every preset is offered, and the tsunami restarts with its sea floor raised") {
    assertEquals(Catalog.shallow.presets.size, 5)
    val preset = Catalog.shallow.presets.find(_.name == "Tsunami").get
    Catalog.shallow.presetCommand(preset, Map.empty) match
      case physis.core.live.Command.Restart(values, _, Some(paint)) =>
        assertEquals(values("edges"), 2.0)
        assert(paint.nonEmpty)
      case other => fail(s"expected a restart, got $other")
  }

  test("the shallow-water session can save images and animations") {
    val session = Catalog.shallow.open(seed = 2L, startPaused = true)
    assert(session.hasImage)
    assertEquals(session.animation.every, 10L)
  }
