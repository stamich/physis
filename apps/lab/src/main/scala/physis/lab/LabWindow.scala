package physis.lab

import javafx.geometry.{Insets, Pos}
import javafx.scene.{Node, Scene}
import javafx.scene.control.{ComboBox, Label, ScrollPane, TextInputControl}
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.layout.{
  BorderPane,
  ColumnConstraints,
  GridPane,
  HBox,
  Priority,
  RowConstraints,
  VBox
}

import physis.core.live.Command
import physis.fx.{
  Fx,
  ParamPanel,
  PlotRenderer,
  RenderLoop,
  Renderer,
  RunControls,
  SceneRenderer,
  StatsOverlay,
  Theme,
  TrajectoryRenderer
}
import physis.view.{Direction, View}

/**
 * The Physis Lab window contents: model picker, parameter panel and run controls on the left,
 * the model's views in the centre, statistics and status at the bottom.
 *
 * Switching models stops the current live run and render loop and starts new ones; nothing is
 * shared between sessions except the window. All methods run on the FX application thread.
 */
final class LabWindow(models: Vector[LabModel], seed: Long = 1L):
  require(models.nonEmpty, "the Lab needs at least one model")

  val root: BorderPane = new BorderPane()
  root.setStyle(s"-fx-background-color: ${toCss(Theme.Background)};")

  private val picker = new ComboBox[String]()
  picker.getItems.addAll(models.map(_.title)*)
  picker.setMaxWidth(Double.MaxValue)

  private val side = new VBox(8)
  side.setPadding(new Insets(8))
  side.setPrefWidth(330)
  side.setStyle("-fx-background-color: #f4f5f7;")

  private val overlay = new StatsOverlay()
  overlay.node.setTextFill(Theme.Text)

  /** Status messages (manifest saves and replays). */
  val status: Label = new Label("")
  status.setTextFill(Theme.Text)
  status.setPadding(new Insets(4, 8, 4, 8))

  private val bottom = new HBox(16, overlay.node, status)
  bottom.setAlignment(Pos.CENTER_LEFT)
  root.setLeft(side)
  root.setBottom(bottom)

  private var running: Option[LabWindow.Running] = None

  Fx.onChange(picker.getSelectionModel.selectedIndexProperty) { i =>
    val index = i.intValue
    if index >= 0 && !running.exists(_.session.model eq models(index)) then open(models(index).id)
  }

  /** The current session, if any. */
  def session: Option[LabSession] = running.map(_.session)

  /** The current render loop, if any. */
  def loop: Option[RenderLoop] = running.map(_.loop)

  /** The current run controls, if any. */
  def controls: Option[RunControls] = running.map(_.controls)

  /** Opens model `id`, closing the current session first. */
  def open(id: String, startPaused: Boolean = false): Unit =
    val model =
      models.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"unknown model '$id'"))
    close()
    val session = model.open(seed, startPaused)
    val (viewNode, renderers) = LabWindow.build(session.view)
    val loop = new RenderLoop(session.live, renderers, Some(overlay))
    val panel = new ParamPanel(session.schema, session.initial, loop.setParam)
    val controls = new RunControls(session.live.send)
    var lastStep = -1L
    loop.onFrame { _ =>
      loop.snapshot.foreach { s =>
        controls.refresh(s)
        // after a reset the run is back at its initial parameters: show them
        if s.step < lastStep then panel.refresh(s)
        lastStep = s.step
      }
    }
    val scroll = new ScrollPane(panel.node)
    scroll.setFitToWidth(true)
    VBox.setVgrow(scroll, Priority.ALWAYS)
    side.getChildren.setAll(new Label("Model"), picker, scroll, controls.node)
    root.setCenter(viewNode)
    running = Some(LabWindow.Running(session, loop, panel, controls))
    picker.getSelectionModel.select(models.indexOf(model))
    status.setText("")
    session.live.start()
    loop.start()

  /** Stops the current session's render loop and simulation thread. */
  def close(): Unit =
    running.foreach { r =>
      r.loop.stop()
      r.session.live.stop()
    }
    running = None

  /** Keyboard shortcuts: Space pause/resume, Right single step, R reset, + / - speed. */
  def installShortcuts(scene: Scene): Unit =
    scene.addEventFilter(KeyEvent.KEY_PRESSED, (e: KeyEvent) => shortcut(e))

  private def shortcut(e: KeyEvent): Unit =
    val typing = Option(e.getTarget).exists(_.isInstanceOf[TextInputControl])
    if !typing then
      for r <- running do
        e.getCode match
          case KeyCode.SPACE =>
            r.controls.togglePause()
            e.consume()
          case KeyCode.RIGHT =>
            r.session.live.send(Command.StepOnce)
            e.consume()
          case KeyCode.R =>
            r.session.live.send(Command.Reset)
            e.consume()
          case KeyCode.PLUS | KeyCode.EQUALS | KeyCode.ADD =>
            r.controls.changeSpeed(2.0)
            e.consume()
          case KeyCode.MINUS | KeyCode.SUBTRACT =>
            r.controls.changeSpeed(0.5)
            e.consume()
          case _ => ()

  private def toCss(c: javafx.scene.paint.Color): String =
    f"#${(c.getRed * 255).round}%02x${(c.getGreen * 255).round}%02x${(c.getBlue * 255).round}%02x"

object LabWindow:
  private final case class Running(
      session: LabSession,
      loop: RenderLoop,
      panel: ParamPanel,
      controls: RunControls
  )

  /** The node for a view and the renderers inside it; layouts split their area equally. */
  def build(view: View): (Node, Seq[Renderer]) = view match
    case v: View.Trajectory =>
      val r = new TrajectoryRenderer(v)
      (r.node, Seq(r))
    case v: View.Plot =>
      val r = new PlotRenderer(v)
      (r.node, Seq(r))
    case v: View.Scene2D =>
      val r = new SceneRenderer(v)
      (r.node, Seq(r))
    case View.Layout(views, direction) =>
      val parts = views.map(build)
      val grid = new GridPane()
      grid.setHgap(2)
      grid.setVgap(2)
      val share = 100.0 / math.max(1, parts.size)
      for ((node, _), i) <- parts.zipWithIndex do
        direction match
          case Direction.Horizontal =>
            val c = new ColumnConstraints()
            c.setPercentWidth(share)
            grid.getColumnConstraints.add(c)
            grid.add(node, i, 0)
          case Direction.Vertical =>
            val c = new RowConstraints()
            c.setPercentHeight(share)
            grid.getRowConstraints.add(c)
            grid.add(node, 0, i)
        GridPane.setHgrow(node, Priority.ALWAYS)
        GridPane.setVgrow(node, Priority.ALWAYS)
      direction match
        case Direction.Horizontal =>
          val r = new RowConstraints()
          r.setVgrow(Priority.ALWAYS)
          grid.getRowConstraints.add(r)
        case Direction.Vertical =>
          val c = new ColumnConstraints()
          c.setHgrow(Priority.ALWAYS)
          grid.getColumnConstraints.add(c)
      (grid, parts.flatMap(_._2))
