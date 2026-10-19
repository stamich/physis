package physis.fx

import java.util.concurrent.{CountDownLatch, TimeUnit}

import javafx.scene.Scene
import javafx.scene.layout.{BorderPane, Region}
import javafx.stage.Stage

import physis.core.Stepping
import physis.core.live.{Command, LiveRun, Snapshot, StateExport}
import physis.core.run.Physis

class FrameStatsSuite extends munit.FunSuite:
  test("fps from the median interval, p99 from the slowest frames") {
    val s = new FrameStats(window = 100)
    assert(s.fps.isNaN)
    var t = 0L
    s.record(t)
    for i <- 1 to 100 do
      t += (if i % 50 == 0 then 40_000_000L else 16_666_667L)
      s.record(t)
    assertEqualsDouble(s.fps, 60.0, 0.01)
    assertEqualsDouble(s.p99Millis, 40.0, 1e-9)
    assertEquals(s.frames, 101L)
  }

  test("only the last window counts") {
    val s = new FrameStats(window = 10)
    var t = 0L
    for _ <- 0 to 50 do
      t += 100_000_000L
      s.record(t)
    for _ <- 1 to 10 do
      t += 10_000_000L
      s.record(t)
    assertEqualsDouble(s.medianMillis, 10.0, 1e-9)
  }

class StatsOverlaySuite extends munit.FunSuite:
  test("formats the statistics line") {
    val text = StatsOverlay.format(59.6, 17.04, 1_234_567, 12.346, 3, 0.5, paused = true, "")
    assertEquals(
      text,
      "60 fps  ·  p99 17.0 ms  ·  1.23 M steps/s  ·  t = 12.35 s  ·  rejected 3  ·  speed 0.500x  ·  paused"
    )
    assert(StatsOverlay.format(Double.NaN, Double.NaN, 0, 0, 0, 1, false, "bad").contains("- fps"))
    assert(StatsOverlay.format(1, 1, 1, 1, 0, 1, false, "bad").endsWith("error: bad"))
    assertEquals(StatsOverlay.rate(950), "950")
    assertEquals(StatsOverlay.rate(12_340), "12.3 k")
  }

/** Counts calls; draws nothing. */
final class CountingRenderer extends Renderer:
  val node: Region = new CanvasPane
  @volatile var accepted = 0
  @volatile var rendered = 0
  @volatile var resets = 0
  @volatile var lastTime = 0.0
  def accept(snapshot: Snapshot): Unit =
    accepted += 1
    lastTime = snapshot.time
  def render(): Unit = rendered += 1
  def dirty: Boolean = false
  def reset(): Unit = resets += 1

class RenderLoopSuite extends FxSuite:
  private def liveSpin(): LiveRun[SpinParams, SpinState] =
    new LiveRun(
      Physis.model(Spin).withParams(SpinParams.schema.defaults).stepping(Stepping.Fixed(0.001)),
      summon[StateExport[SpinState]]
    )

  fxTest("renders 120 frames from a live run") {
    val live = liveSpin()
    val renderer = new CountingRenderer
    val overlay = new StatsOverlay(refreshEvery = 1)
    val loop = new RenderLoop(live, Seq(renderer), Some(overlay))
    val done = new CountDownLatch(1)
    loop.onFrame(f => if f.number >= 120 then done.countDown())
    live.start()
    val stage = FxTesting.onFx {
      val root = new BorderPane(renderer.node)
      root.setBottom(overlay.node)
      val stage = new Stage()
      stage.setScene(new Scene(root, 320, 240))
      stage.show()
      loop.start()
      stage
    }
    try
      assert(done.await(30, TimeUnit.SECONDS), s"only ${loop.stats.frames} frames")
      FxTesting.onFx(loop.stop())
      assert(renderer.accepted > 10, s"accepted ${renderer.accepted}")
      assertEquals(renderer.rendered, renderer.accepted)
      assert(renderer.lastTime > 0.0)
      assert(FxTesting.onFx(overlay.node.getText).contains("fps"))
    finally
      FxTesting.onFx(stage.close())
      live.stop()
  }

  fxTest("parameter changes of a frame go out as one command; reset clears renderers") {
    val live = liveSpin()
    val renderer = new CountingRenderer
    val loop = new RenderLoop(live, Seq(renderer))
    live.start()
    try
      FxTesting.onFx {
        loop.setParam("omega", 2.0)
        loop.setParam("omega", 3.0)
        loop.setParam("r", 2.0)
        loop.start()
      }
      val deadline = System.currentTimeMillis() + 10_000
      while FxTesting.onFx(loop.snapshot.map(s => s.param("omega") != 3.0 || s.param("r") != 2.0))
          .getOrElse(true)
      do
        assert(System.currentTimeMillis() < deadline, "parameter change not seen")
        Thread.sleep(20)
      assertEquals(live.manifest().events.size, 1)
      live.send(Command.Reset)
      while renderer.resets == 0 do
        assert(System.currentTimeMillis() < deadline, "reset not seen")
        Thread.sleep(20)
    finally
      FxTesting.onFx(loop.stop())
      live.stop()
  }
