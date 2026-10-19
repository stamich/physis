package physis.fx

import javafx.event.{Event, EventType}
import javafx.scene.Scene
import javafx.scene.image.ImageView
import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.layout.StackPane
import javafx.stage.Stage

import physis.core.live.{Slice, Snapshot}
import physis.view.{Colormap, Dab, ValueRange, View}

class HeatmapPointerSuite extends FxSuite:
  private val view: View.Heatmap =
    View.Heatmap(
      "u",
      ValueRange.SymmetricAuto,
      Colormap.Diverging,
      "wave",
      overlay = Some("wall")
    ) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  private val N = 64

  private def snapshot(): Snapshot =
    val s = new Snapshot(2 * N * N, 0)
    for k <- 0 until N * N do s.state(k) = math.sin(k * 0.01)
    s.slices = Vector(Slice("u", 0, N, N), Slice("wall", N * N, N, N))
    s

  private def mouse(
      kind: EventType[MouseEvent],
      x: Double,
      y: Double,
      button: MouseButton = MouseButton.PRIMARY,
      sceneX: Double = Double.NaN,
      sceneY: Double = Double.NaN
  ): MouseEvent =
    new MouseEvent(
      kind,
      x,
      y,
      if sceneX.isNaN then x else sceneX,
      if sceneY.isNaN then y else sceneY,
      button,
      1,
      false,
      false,
      false,
      false,
      button == MouseButton.PRIMARY,
      false,
      button == MouseButton.SECONDARY,
      false,
      false,
      true,
      null
    )

  private def scroll(x: Double, y: Double, deltaY: Double): ScrollEvent =
    new ScrollEvent(
      ScrollEvent.SCROLL,
      x,
      y,
      x,
      y,
      false,
      false,
      false,
      false,
      false,
      false,
      0.0,
      deltaY,
      0.0,
      deltaY,
      ScrollEvent.HorizontalTextScrollUnits.NONE,
      0.0,
      ScrollEvent.VerticalTextScrollUnits.NONE,
      0.0,
      0,
      null
    )

  /** A renderer with a frame, shown and laid out; returns it, the stage and cells' pixel size. */
  private def shown(): (HeatmapRenderer, Stage, Double) =
    val r = FxTesting.onFx(new HeatmapRenderer(view))
    val stage = FxTesting.onFx {
      r.accept(snapshot())
      r.render()
      val st = new Stage()
      st.setScene(new Scene(new StackPane(r.node), 420, 360))
      st.show()
      r.node.applyCss()
      r.node.layout()
      st
    }
    // events are given in cells and turned into pixels when they are sent, because the window
    // may still be resized by a pulse or two on a slow machine
    val cell = 1.0
    (r, stage, cell)

  /** Pixels per cell of the image as it is laid out right now. */
  private def pixelsPerCell(r: HeatmapRenderer): Double =
    r.pointerTarget.asInstanceOf[ImageView].getFitWidth / N

  /**
   * Fires `e` and then evaluates `read`, in one step on the FX thread so that no layout pass
   * comes between them. A mouse event's x and y are in cells and are turned into pixels here.
   */
  private def fireThen[A](r: HeatmapRenderer, e: Event)(read: => A): A =
    FxTesting.onFx {
      val placed = e match
        case m: MouseEvent =>
          val sc = pixelsPerCell(r)
          val x = m.getX * sc
          val y = m.getY * sc
          val p = r.pointerTarget.localToScene(x, y)
          val kind = m.getEventType.asInstanceOf[EventType[MouseEvent]]
          // the constructor takes the scene position; dispatch turns it back into local x and y
          mouse(kind, p.getX, p.getY, m.getButton)
        case other => other
      Event.fireEvent(r.pointerTarget, placed)
      read
    }

  private def fire(r: HeatmapRenderer, e: Event): Unit = fireThen(r, e)(())

  private def collect(r: HeatmapRenderer): scala.collection.mutable.Buffer[StrokeBatch] =
    val batches = scala.collection.mutable.Buffer.empty[StrokeBatch]
    FxTesting.onFx { r.onStroke = b => batches += b }
    batches

  private def frame(r: HeatmapRenderer): Unit = FxTesting.onFx(r.render())

  private def gap(a: Dab, b: Dab): Double = math.hypot(a.x - b.x, a.y - b.y)

  fxTest("a left drag becomes gap-free dabs in fractional cell coordinates, one batch per frame") {
    val (r, stage, cell) = shown()
    try
      FxTesting.onFx(r.setBrush(true, 4.0))
      val batches = collect(r)
      fire(r, mouse(MouseEvent.MOUSE_PRESSED, 10.25 * cell, 20.5 * cell))
      fire(r, mouse(MouseEvent.MOUSE_DRAGGED, 20.0 * cell, 20.5 * cell))
      fire(r, mouse(MouseEvent.MOUSE_DRAGGED, 30.0 * cell, 20.5 * cell))
      assertEquals(batches.size, 0)
      frame(r)
      assertEquals(batches.size, 1)
      val first = batches.head
      assert(!first.secondary && !first.finished)
      assertEqualsDouble(first.dabs.head.x, 10.25, 1e-6)
      assertEqualsDouble(first.dabs.head.y, 20.5, 1e-6)
      assertEqualsDouble(first.dabs.last.x, 30.0, 1e-6)
      first.dabs.zip(first.dabs.tail).foreach((a, b) => assert(gap(a, b) <= 2.0 + 1e-6))
      frame(r)
      assertEquals(batches.size, 1)
      fire(r, mouse(MouseEvent.MOUSE_RELEASED, 30.0 * cell, 20.5 * cell))
      frame(r)
      assertEquals(batches.size, 2)
      assert(batches(1).finished)
      frame(r)
      assertEquals(batches.size, 2)
    finally FxTesting.onFx(stage.close())
  }

  fxTest("the right button makes a secondary stroke") {
    val (r, stage, cell) = shown()
    try
      FxTesting.onFx(r.setBrush(true, 3.0))
      val batches = collect(r)
      fire(r, mouse(MouseEvent.MOUSE_PRESSED, 5.0 * cell, 5.0 * cell, MouseButton.SECONDARY))
      fire(r, mouse(MouseEvent.MOUSE_RELEASED, 5.0 * cell, 5.0 * cell, MouseButton.SECONDARY))
      frame(r)
      assertEquals(batches.size, 1)
      assert(batches.head.secondary && batches.head.finished)
      assertEquals(batches.head.dabs.size, 1)
    finally FxTesting.onFx(stage.close())
  }

  fxTest("with the brush off the pointer only reads cells") {
    val (r, stage, cell) = shown()
    try
      val batches = collect(r)
      fire(r, mouse(MouseEvent.MOUSE_PRESSED, 10.0 * cell, 10.0 * cell))
      fire(r, mouse(MouseEvent.MOUSE_DRAGGED, 20.0 * cell, 10.0 * cell))
      fire(r, mouse(MouseEvent.MOUSE_RELEASED, 20.0 * cell, 10.0 * cell))
      frame(r)
      assertEquals(batches.size, 0)
      fire(r, mouse(MouseEvent.MOUSE_MOVED, 10.0 * cell, 10.0 * cell))
      assert(!FxTesting.onFx(r.ringNode.isVisible))
    finally FxTesting.onFx(stage.close())
  }

  fxTest("the ring follows the pointer and is never drawn into the image") {
    val (r, stage, cell) = shown()
    try
      FxTesting.onFx(r.setBrush(true, 6.0))
      val before = FxTesting.onFx(r.lastPixels.clone())
      val ring = FxTesting.onFx(r.ringNode.asInstanceOf[javafx.scene.shape.Circle])
      val (visible, radius, centre, offset, sc) =
        fireThen(r, mouse(MouseEvent.MOUSE_MOVED, 16.0 * cell, 24.0 * cell)) {
          (
            ring.isVisible,
            ring.getRadius,
            ring.getCenterX,
            r.pointerTarget.getLayoutX,
            pixelsPerCell(r)
          )
        }
      frame(r)
      assert(visible)
      assertEqualsDouble(radius, 6.0 * sc, 1e-6)
      assertEqualsDouble(centre, offset + 16.0 * sc, 1e-6)
      assertEquals(FxTesting.onFx(r.lastPixels.toVector), before.toVector)
      val (centre2, offset2, sc2) =
        fireThen(r, mouse(MouseEvent.MOUSE_MOVED, 30.0 * cell, 24.0 * cell)) {
          (ring.getCenterX, r.pointerTarget.getLayoutX, pixelsPerCell(r))
        }
      assertEqualsDouble(centre2, offset2 + 30.0 * sc2, 1e-6)
      fire(r, mouse(MouseEvent.MOUSE_EXITED, 30.0 * cell, 24.0 * cell))
      assert(!FxTesting.onFx(ring.isVisible))
    finally FxTesting.onFx(stage.close())
  }

  fxTest("the wheel changes the radius within 1 and 64 and reports it") {
    val (r, stage, cell) = shown()
    try
      FxTesting.onFx(r.setBrush(true, 8.0))
      val seen = scala.collection.mutable.Buffer.empty[Double]
      FxTesting.onFx { r.onRadius = v => seen += v }
      fire(r, scroll(10.0 * cell, 10.0 * cell, 40.0))
      assertEquals(FxTesting.onFx(r.radius), 9.0)
      fire(r, scroll(10.0 * cell, 10.0 * cell, -40.0))
      fire(r, scroll(10.0 * cell, 10.0 * cell, -40.0))
      assertEquals(FxTesting.onFx(r.radius), 7.0)
      assertEquals(seen.toVector, Vector(9.0, 8.0, 7.0))
      FxTesting.onFx(r.setBrush(true, 500.0))
      assertEquals(FxTesting.onFx(r.radius), 64.0)
      FxTesting.onFx(r.setBrush(true, 0.0))
      assertEquals(FxTesting.onFx(r.radius), 1.0)
    finally FxTesting.onFx(stage.close())
  }

  fxTest("a drag over a periodic seam continues on the other side") {
    val (r, stage, cell) = shown()
    try
      FxTesting.onFx { r.setBrush(true, 4.0); r.setWrap(true, true) }
      val batches = collect(r)
      fire(r, mouse(MouseEvent.MOUSE_PRESSED, 62.0 * cell, 30.0 * cell))
      fire(r, mouse(MouseEvent.MOUSE_DRAGGED, 66.0 * cell, 30.0 * cell))
      frame(r)
      val dabs = batches.flatMap(_.dabs)
      assert(dabs.forall(d => d.x >= 0.0 && d.x < N), dabs.toString)
      assert(dabs.exists(_.x < 3.0))
    finally FxTesting.onFx(stage.close())
  }
