package physis.fx

import javafx.scene.Scene
import javafx.scene.control.{CheckBox, Slider, Spinner, TextField}
import javafx.scene.layout.VBox
import javafx.stage.Stage

import physis.core.Stepping
import physis.core.live.{Command, LiveRun, StateExport}
import physis.core.param.{ParamKind, ParamSpec, Scale}
import physis.core.run.Physis

class ControlSpecSuite extends munit.FunSuite:
  private val specs = ControlSpec.of(SpinParams.schema)
  private def named(n: String) = specs.find(_.name == n).get

  test("each parameter kind maps to its control") {
    assertEquals(specs.map(_.name), Vector("omega", "r", "n", "on"))
    assertEquals(named("omega").control, ControlKind.Slider(0.0, 100.0, log = false))
    assertEquals(named("r").control, ControlKind.Slider(1e-3, 1e3, log = true))
    assertEquals(named("n").control, ControlKind.Spinner(1, 10))
    assertEquals(named("on").control, ControlKind.CheckBox)
    val unbounded = ParamSpec("x", default = 0.0)
    assertEquals(ControlSpec.control(unbounded, ParamKind.Real), ControlKind.NumberField)
    val fromZero = ParamSpec("eps", default = 1e-3, min = 0.0, max = 1.0, scale = Scale.Log)
    assertEquals(
      ControlSpec.control(fromZero, ParamKind.Real),
      ControlKind.Slider(1e-12, 1.0, log = true)
    )
  }

  test("labels carry the unit") {
    assertEquals(named("omega").label, "omega [rad/s]")
    assertEquals(named("n").label, "n")
  }

  test("log sliders slide over log10 and clamp to the range") {
    val r = named("r")
    assertEqualsDouble(r.toSlider(100.0), 2.0, 1e-12)
    assertEqualsDouble(r.fromSlider(-1.0), 0.1, 1e-12)
    assertEquals(r.fromSlider(9.0), 1e3)
    assertEquals(named("n").fromSlider(3.6), 4.0)
  }

  test("text input is parsed and validated against the spec") {
    val omega = named("omega")
    assertEquals(omega.parse(" 2,5 "), Right(2.5))
    assertEquals(omega.parse("1e1"), Right(10.0))
    assert(omega.parse("abc").isLeft)
    assert(omega.parse("-1").left.exists(_.contains("[0.0, 100.0]")))
    assert(named("n").parse("2.5").left.exists(_.contains("whole")))
    assertEquals(named("on").parse("true"), Right(1.0))
    assert(named("on").parse("0.5").isLeft)
  }

class ParamPanelSuite extends FxSuite:
  fxTest("editing the panel sends SetParams through the render loop") {
    val live = new LiveRun(
      Physis.model(Spin).withParams(SpinParams.schema.defaults).stepping(Stepping.Fixed(0.001)),
      summon[StateExport[SpinState]]
    )
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[Command]()
    val loop = new RenderLoop(live, Seq.empty)
    val panel = new ParamPanel(SpinParams.schema, Map.empty, loop.setParam)
    val controls = new RunControls(c =>
      sent.add(c); live.send(c)
    )
    live.start()
    val stage = FxTesting.onFx {
      val stage = new Stage()
      stage.setScene(new Scene(new VBox(panel.node, controls.node), 360, 300))
      stage.show()
      loop.start()
      stage
    }
    try
      FxTesting.onFx {
        panel.controls("omega").collectFirst { case s: Slider => s }.get.setValue(7.0)
        panel.controls("n").collectFirst { case s: Spinner[?] => s }.get.increment(2)
        panel.controls("on").collectFirst { case b: CheckBox => b }.get.setSelected(false)
        val field = panel.controls("r").collectFirst { case f: TextField => f }.get
        field.setText("-5")
        field.fireEvent(new javafx.event.ActionEvent())
        assert(field.getStyle.contains("border-color"), "invalid input is marked")
      }
      val deadline = System.currentTimeMillis() + 10_000
      def seen =
        FxTesting.onFx(loop.snapshot.map(s => (s.param("omega"), s.param("n"), s.param("on"))))
      while !seen.contains((7.0, 5.0, 0.0)) do
        assert(System.currentTimeMillis() < deadline, s"panel changes not applied: $seen")
        Thread.sleep(20)
      assertEquals(FxTesting.onFx(loop.snapshot.map(_.param("r"))), Some(1.0))
      FxTesting.onFx {
        controls.togglePause()
        controls.stepOnce.fire()
        controls.changeSpeed(10.0)
      }
      val commands = Iterator.continually(sent.poll()).takeWhile(_ != null).toVector
      assertEquals(commands.take(2), Vector(Command.Pause, Command.StepOnce))
      assert(commands.exists {
        case Command.SetSpeed(s) => math.abs(s - 10.0) < 1e-9; case _ => false
      })
    finally
      FxTesting.onFx(loop.stop())
      FxTesting.onFx(stage.close())
      live.stop()
  }
