package physis.fx

class ToolBoxSuite extends FxSuite:

  private val tools = Vector(
    ToolSpec("Paint", Some("v"), 0.5, (0.0, 1.0), (1.0, 64.0), 6.0),
    ToolSpec("Disturb", Some("strength"), 0.5, (0.05, 2.0), (10.0, 400.0), 80.0)
  )

  fxTest("no tool is on at first; picking one sets its radius and value, B toggles") {
    var changes = 0
    val box = FxTesting.onFx(new ToolBox(tools, () => changes += 1))
    FxTesting.onFx {
      assertEquals(box.selected, None)
      box.select(Some("Disturb"))
      assertEquals(box.selected.map(_.name), Some("Disturb"))
      assertEquals(box.radius, 80.0)
      assertEquals(box.value, 0.5)
      box.setRadius(1000.0)
      assertEquals(box.radius, 400.0)
      box.toggle()
      assertEquals(box.selected, None)
      box.toggle()
      assertEquals(box.selected.map(_.name), Some("Disturb"))
      assert(changes > 0)
    }
  }

  fxTest("a value outside the tool's range is refused and the last good one stays") {
    val box = FxTesting.onFx(new ToolBox(tools))
    FxTesting.onFx {
      box.select(Some("Paint"))
      box.valueField.setText("0.8")
      box.valueField.fireEvent(new javafx.event.ActionEvent())
      assertEquals(box.value, 0.8)
      box.valueField.setText("7")
      box.valueField.fireEvent(new javafx.event.ActionEvent())
      assertEquals(box.value, 0.8)
    }
  }

  fxTest("the counter says how many strokes were recorded") {
    val box = FxTesting.onFx(new ToolBox(tools))
    FxTesting.onFx {
      box.setStrokes(1)
      assertEquals(box.counter.getText, "1 stroke recorded")
      box.setStrokes(5)
      assertEquals(box.counter.getText, "5 strokes recorded")
    }
  }
