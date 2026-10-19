package physis.fx

import javafx.event.ActionEvent
import javafx.scene.control.{Spinner, TextField}

import physis.core.live.Command
import physis.core.param.Preset
import physis.models.grayscott.GrayScott

class LabControlsSuite extends FxSuite:

  private def record[A](buffer: scala.collection.mutable.ArrayBuffer[A])(a: A): Unit =
    val _ = buffer += a

  fxTest("restart-only values wait for Apply; the other parameters go through at once") {
    val changes = scala.collection.mutable.ArrayBuffer.empty[(String, Double)]
    val restarts = scala.collection.mutable.ArrayBuffer.empty[Map[String, Double]]
    val panel = FxTesting.onFx(
      new ParamPanel(
        GrayScott.params,
        GrayScott.params.toMap(GrayScott.params.defaults).toMap,
        (n, v) => record(changes)(n -> v),
        record(restarts)(_)
      )
    )
    FxTesting.onFx {
      assert(panel.applyButton.isDisabled)
      val nx = panel.controls("nx").head.asInstanceOf[Spinner[Integer]]
      nx.getValueFactory.setValue(Integer.valueOf(96))
      assertEquals(panel.pendingRestart, Map("nx" -> 96.0))
      assert(changes.isEmpty, s"restart value was sent: $changes")
      assert(!panel.applyButton.isDisabled)
      val field = panel.controls("F").last.asInstanceOf[TextField]
      field.setText("0.03")
      field.fireEvent(new ActionEvent())
      assertEquals(changes.toSeq, Seq("F" -> 0.03))
      panel.applyButton.fire()
      assertEquals(restarts.toSeq, Seq(Map("nx" -> 96.0)))
      assertEquals(panel.pendingRestart, Map.empty[String, Double])
      assert(panel.applyButton.isDisabled)
      nx.getValueFactory.setValue(Integer.valueOf(48))
      panel.discardEdits()
      assertEquals(panel.pendingRestart, Map.empty[String, Double])
      assert(panel.applyButton.isDisabled)
    }
  }

  fxTest("choosing a preset applies all its values at once") {
    val applied = scala.collection.mutable.ArrayBuffer.empty[Preset]
    val picker = FxTesting.onFx(new PresetPicker(GrayScott.presets, record(applied)(_)))
    FxTesting.onFx(picker.choose("Maze"))
    assertEquals(applied.map(_.name).toSeq, Seq("Maze"))
    assertEquals(applied.head.values, Map("F" -> 0.0290, "k" -> 0.0570))
  }

  fxTest("the seed bar sends whole numbers only") {
    val seeds = scala.collection.mutable.ArrayBuffer.empty[Long]
    val bar = FxTesting.onFx(new SeedBar(1L, record(seeds)(_)))
    FxTesting.onFx {
      bar.field.setText(" 42 ")
      assert(bar.commit())
      bar.field.setText("4x")
      assert(!bar.commit())
      assert(bar.field.getStyle.contains("e15759"))
      bar.field.setText("-7")
      bar.button.fire()
    }
    assertEquals(seeds.toSeq, Seq(42L, -7L))
  }

  fxTest("the speed slider follows the range it is given") {
    val sent = scala.collection.mutable.ArrayBuffer.empty[Command]
    val controls = FxTesting.onFx(
      new RunControls(record(sent)(_), 600.0, (10.0, 4000.0), s => f"$s%.0f steps/s")
    )
    FxTesting.onFx {
      assertEqualsDouble(controls.speed.getMin, 1.0, 1e-12)
      assertEqualsDouble(controls.speed.getMax, math.log10(4000.0), 1e-12)
      assertEqualsDouble(controls.speed.getValue, math.log10(600.0), 1e-12)
      controls.changeSpeed(1000.0)
      assertEqualsDouble(controls.speed.getValue, math.log10(4000.0), 1e-12)
      controls.changeSpeed(1e-6)
      assertEqualsDouble(controls.speed.getValue, 1.0, 1e-12)
    }
    assertEquals(sent.last, Command.SetSpeed(10.0))
  }
