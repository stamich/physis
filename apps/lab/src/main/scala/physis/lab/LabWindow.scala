package physis.lab

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.{Node, Scene}
import javafx.scene.control.{
  Button,
  ComboBox,
  Label,
  Menu,
  MenuBar,
  MenuItem,
  ProgressBar,
  ScrollPane,
  TextInputControl
}
import javafx.stage.FileChooser
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
  HeatmapRenderer,
  Paintable,
  ParamPanel,
  ParticlesRenderer,
  PresetPicker,
  PlotRenderer,
  RenderLoop,
  Renderer,
  RunControls,
  SceneRenderer,
  SeedBar,
  StatsOverlay,
  Theme,
  ToolBox,
  TrajectoryRenderer
}
import physis.view.{Direction, Frame, FrameImage, Frames, Png, View}

/**
 * The Physis Lab window contents: model picker, parameter panel and run controls on the left,
 * the model's views in the centre, statistics and status at the bottom.
 *
 * Switching models stops the current live run and render loop and starts new ones; nothing is
 * shared between sessions except the window. All methods run on the FX application thread.
 */
final class LabWindow(
    models: Vector[LabModel],
    registry: physis.core.run.ModelRegistry = Catalog.registry,
    seed: Long = 1L
):
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

  /** The value of the heatmap cell under the pointer, e.g. `v[12, 40] = 0.2481`. */
  val cell: Label = new Label("")
  cell.setTextFill(Theme.Text)
  cell.setPadding(new Insets(4, 8, 4, 8))

  /** File → Save animation…; disabled when the model has no image. */
  val animationItem: MenuItem = new MenuItem("Save animation…")
  animationItem.setDisable(true)

  /** Progress of a Save animation and the button that stops it; shown only while one runs. */
  val animationProgress: ProgressBar = new ProgressBar(0.0)
  animationProgress.setPrefWidth(160)
  val animationCancel: Button = new Button("Cancel")
  private val animationBox = new HBox(8, animationProgress, animationCancel)
  animationBox.setAlignment(Pos.CENTER_LEFT)
  animationBox.setVisible(false)
  animationBox.setManaged(false)
  animationCancel.setOnAction(_ => cancelAnimation())

  private val bottom = new HBox(16, overlay.node, cell, status, animationBox)
  bottom.setAlignment(Pos.CENTER_LEFT)
  root.setLeft(side)
  root.setBottom(bottom)
  root.setTop(menu())

  private def menu(): MenuBar =
    val save = new MenuItem("Save manifest…")
    save.setOnAction(_ => chooseAndSave())
    val image = new Menu("Save image")
    for factor <- LabWindow.ImageScales do
      val item = new MenuItem(s"$factor×")
      item.setOnAction(_ => chooseAndSaveImage(factor))
      image.getItems.add(item)
    animationItem.setOnAction(_ => chooseAndSaveAnimation())
    val replay = new MenuItem("Replay manifest…")
    replay.setOnAction(_ => chooseAndReplay())
    val quit = new MenuItem("Close")
    quit.setOnAction(_ => Option(root.getScene).map(_.getWindow).foreach(_.hide()))
    new MenuBar(new Menu("File", null, save, image, animationItem, replay, quit))

  private def chooser(): FileChooser =
    val c = new FileChooser()
    c.getExtensionFilters.add(new FileChooser.ExtensionFilter("Physis manifest", "*.json"))
    c

  private def chooseAndSave(): Unit =
    for r <- running do
      val c = chooser()
      c.setInitialFileName(s"${r.session.model.id}-session.json")
      Option(c.showSaveDialog(root.getScene.getWindow)).foreach(f => saveManifest(f.toPath))

  private def chooseAndSaveImage(factor: Int): Unit =
    for r <- running do
      val c = new FileChooser()
      c.getExtensionFilters.add(new FileChooser.ExtensionFilter("PNG image", "*.png"))
      c.setInitialFileName(s"${r.session.model.id}.png")
      Option(c.showSaveDialog(root.getScene.getWindow)).foreach(f => saveImage(f.toPath, factor))

  /** The pictures per second the session has run at, kept for the time estimate while paused. */
  private var lastRate = 0.0

  private def chooseAndSaveAnimation(): Unit =
    for r <- running if r.session.hasImage; shot <- r.loop.snapshot do
      if shot.step < 1L then status.setText("Nothing has run yet: start the session first")
      else
        val cells = LabAnimations
          .pictureSize(r.session, shot, AnimationOptions(1L, colorBar = false))
          .fold(256)(_._1)
        val initial = LabAnimations.suggest(shot.step, r.session.model.animationDefaults, cells)
        val form = new AnimationForm(r.session, shot, shot.step, lastRate, initial)
        val owner = Option(root.getScene).map(_.getWindow).orNull
        if exporting.isEmpty then
          for options <- AnimationDialog.ask(owner, form) do
            val c = new FileChooser()
            c.getExtensionFilters.add(new FileChooser.ExtensionFilter("Animated PNG", "*.png"))
            c.setInitialFileName(s"${r.session.model.id}-animation.png")
            Option(c.showSaveDialog(owner)).foreach(f => saveAnimation(f.toPath, options))

  /** The cancel flag of the animation being saved, if one is. */
  @volatile private var exporting: Option[AtomicBoolean] = None

  /** Whether an animation is being saved. */
  def savingAnimation: Boolean = exporting.isDefined

  private def refreshAnimationItem(): Unit =
    animationItem.setDisable(exporting.isDefined || !running.exists(_.session.hasImage))

  /** Stops the animation being saved, if any; its file is removed. Safe from any thread. */
  def cancelAnimation(): Unit = exporting.foreach(_.set(true))

  private def animationText(path: Path, result: Either[String, AnimationReport]): String =
    result match
      case Right(report) => report.summary(path)
      case Left(Frames.Cancelled) => "Animation cancelled"
      case Left(e) => s"Could not save the animation: $e"

  /**
   * Replays the session as it is now and saves it as an animated PNG at `path` (see
   * [[LabAnimations]]) on a background thread, so the window stays alive and the session keeps
   * running. The progress bar and the status line follow it, Cancel stops it, and the returned
   * future completes on the FX thread with what came of it. `afterFrame` is called with
   * (frames written, frames in all) on the worker thread after each frame. Call it on the FX
   * thread; one animation at a time.
   */
  def saveAnimation(
      path: Path,
      options: AnimationOptions,
      afterFrame: (Int, Int) => Unit = (_, _) => ()
  ): CompletableFuture[Either[String, AnimationReport]] =
    val done = new CompletableFuture[Either[String, AnimationReport]]()
    def refuse(why: String): CompletableFuture[Either[String, AnimationReport]] =
      status.setText(s"Could not save the animation: $why")
      if !done.complete(Left(why)) then ()
      done
    running match
      case None => refuse("no session is running")
      case Some(_) if exporting.isDefined => refuse("an animation is being saved already")
      case Some(r) =>
        scala.util.Try(r.session.live.manifest()).toEither match
          case Left(e) => refuse(s"the session did not answer: ${e.getMessage}")
          case Right(manifest) =>
            val cancel = new AtomicBoolean(false)
            exporting = Some(cancel)
            refreshAnimationItem()
            animationProgress.setProgress(0.0)
            animationBox.setVisible(true)
            animationBox.setManaged(true)
            status.setText(s"Saving ${path.getFileName}…")
            val started = System.nanoTime()
            def show(frames: Int, total: Int): Unit =
              animationProgress.setProgress(frames.toDouble / math.max(1, total))
              val left =
                (System.nanoTime() - started) / 1e9 * (total - frames) / math.max(1, frames)
              status.setText(
                f"Saving ${path.getFileName}: $frames of $total frames" +
                  (if frames < total then f", about $left%.0f s left" else "")
              )
            def finish(result: Either[String, AnimationReport]): Unit =
              exporting = None
              animationBox.setVisible(false)
              animationBox.setManaged(false)
              refreshAnimationItem()
              status.setText(animationText(path, result))
              if !done.complete(result) then ()
            def work(): Unit =
              val result =
                try
                  LabAnimations.save(
                    r.session,
                    manifest,
                    registry,
                    path,
                    options,
                    (frames, total) =>
                      Platform.runLater(() => show(frames, total))
                      afterFrame(frames, total)
                    ,
                    () => cancel.get
                  )
                catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))
              Platform.runLater(() => finish(result))
            val worker = new Thread(() => work(), "physis-animation")
            worker.setDaemon(true)
            worker.start()
            done

  private def chooseAndReplay(): Unit =
    Option(chooser().showOpenDialog(root.getScene.getWindow)).foreach(f => replayManifest(f.toPath))

  /** Saves the current session's manifest to `path` and reports it in the status line. */
  def saveManifest(path: Path): Either[String, physis.core.run.Manifest] =
    running match
      case None => Left("no session is running")
      case Some(r) =>
        val result = LabManifests.save(r.session.live, path)
        val text = result match
          case Right(m) =>
            val strokes = m.interventions.size
            val painted = if strokes == 1 then "1 stroke" else s"$strokes strokes"
            s"Saved ${path.getFileName}: ${m.steps.getOrElse(0L)} steps, " +
              s"${m.events.size} parameter changes, $painted"
          case Left(error) => error
        status.setText(text)
        result

  /**
   * Saves the first image of the current view (the field as drawn, with its colour scale on the
   * right and no text) to `path` as a PNG, each cell `factor` pixels wide. The status line says
   * where it went and which values the colours stand for. Call it on the FX thread.
   */
  def saveImage(path: Path, factor: Int): Either[String, String] =
    val result: Either[String, String] =
      if factor < 1 || factor > LabWindow.MaxImageScale then
        Left(s"image scale must be 1 to ${LabWindow.MaxImageScale}, got $factor")
      else
        heatmaps.headOption match
          case None => Left("this model has no image to save")
          case Some(h) =>
            val (nx, ny) = h.gridSize
            if nx == 0 then Left("nothing has been drawn yet")
            else
              val (lo, hi) = h.range
              val picture = new Frame(nx, ny, h.lastPixels.clone(), lo, hi)
              val frame = FrameImage.withColorBar(picture, h.colormap)
              try
                Png.write(path, frame.pixels, frame.width, frame.height, factor)
                Right(
                  f"Saved ${path.getFileName}: ${frame.width * factor} x ${frame.height * factor}" +
                    f" px, colours from $lo%.4g (bottom) to $hi%.4g (top)"
                )
              catch case e: java.io.IOException => Left(s"Could not save $path: ${e.getMessage}")
    status.setText(result.merge)
    result

  /**
   * Replays the manifest at `path` on a background thread; the outcome appears in the status line
   * and completes the returned future (on the FX thread).
   */
  def replayManifest(path: Path): CompletableFuture[Either[String, ReplayReport]] =
    val done = new CompletableFuture[Either[String, ReplayReport]]()
    status.setText(s"Replaying ${path.getFileName}…")
    def report(result: Either[String, ReplayReport]): Unit =
      status.setText(result.fold(e => s"Replay failed: $e", _.summary))
      if !done.complete(result) then ()
    def work(): Unit =
      val result = LabManifests.replay(path, registry)
      Platform.runLater(() => report(result))
    val worker = new Thread(() => work(), "physis-replay")
    worker.setDaemon(true)
    worker.start()
    done

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
    val panel = new ParamPanel(
      session.schema,
      session.initial,
      loop.setParam,
      values => session.live.send(Command.Restart(currentParams() ++ values, None))
    )
    val controls = new RunControls(
      session.live.send,
      session.speedRange.default,
      (session.speedRange.min, session.speedRange.max),
      session.speedRange.text
    )
    for case h: HeatmapRenderer <- renderers do
      h.onCell = c => cell.setText(c.fold("")(_.text(h.sliceName)))
    val paintables = renderers.collect { case p: Paintable => p }
    var box: ToolBox = null
    if session.tools.nonEmpty && paintables.nonEmpty then
      box = new ToolBox(
        session.tools.map(_.spec),
        () => if box != null then wireTools(session, box, paintables)
      )
    var lastStep = -1L
    var lastParams: AnyRef = null
    lastRate = 0.0
    animationItem.setDisable(exporting.isDefined || !session.hasImage)
    loop.onFrame { _ =>
      loop.snapshot.foreach { s =>
        if s.stepsPerSecond > 0.0 then lastRate = s.stepsPerSecond
        controls.refresh(s)
        if box != null then
          box.setStrokes(s.interventions)
          if s.paramValues ne lastParams then wireTools(session, box, paintables)
        // after a reset or a restart the run is back at its initial parameters: show them
        if s.step < lastStep then
          panel.discardEdits()
          panel.refresh(s)
        // a preset or a restart changed the parameters from outside the panel
        else if s.paramValues ne lastParams then panel.refresh(s)
        lastParams = s.paramValues
        lastStep = s.step
      }
    }
    val scroll = new ScrollPane(panel.node)
    scroll.setFitToWidth(true)
    VBox.setVgrow(scroll, Priority.ALWAYS)
    val parts = Vector.newBuilder[Node]
    parts += new Label("Model")
    parts += picker
    if session.presets.nonEmpty then
      val presetPicker = new PresetPicker(session.presets, p => applyPreset(p.name))
      parts += presetPicker.node
    parts += scroll
    if box != null then parts += box.node
    if session.usesSeed then parts += new SeedBar(seed, s => reseed(s)).node
    parts += controls.node
    side.getChildren.setAll(parts.result()*)
    root.setCenter(viewNode)
    running = Some(LabWindow.Running(
      session,
      loop,
      panel,
      controls,
      renderers.collect {
        case h: HeatmapRenderer => h
      },
      Option(box),
      paintables
    ))
    picker.getSelectionModel.select(models.indexOf(model))
    status.setText("")
    cell.setText("")
    session.live.start()
    loop.start()

  /**
   * Connects the pointer tools to the session: the selected tool and the box's radius and value
   * decide what a dab means, and every dab becomes one [[Command.Intervene]].
   */
  private def wireTools(session: LabSession, box: ToolBox, paintables: Seq[Paintable]): Unit =
    val tool = box.selected.flatMap(spec => session.tools.find(_.spec == spec))
    val wrap = session.wraps(currentParams())
    for p <- paintables do
      tool.foreach(t => p.radiusRange = t.spec.radiusRange)
      p.setBrush(tool.isDefined, box.radius)
      p.setWrap(wrap, wrap)
      p.onRadius = r => box.setRadius(r)
      p.onStroke = batch =>
        for t <- tool; dab <- batch.dabs do
          val iv = t.make(dab, box.radius, box.value, batch.secondary)
          session.live.send(Command.Intervene(iv))

  /** The renderers of the current session that the pointer can paint on. */
  def paintables: Seq[Paintable] = running.toSeq.flatMap(_.paintables)

  /** The tool box of the current session, if the model has tools. */
  def toolBox: Option[ToolBox] = running.flatMap(_.toolBox)

  /** Applies the preset called `name` of the current model as one parameter change. */
  def applyPreset(name: String): Unit =
    for r <- running; preset <- r.session.presets.find(_.name == name) do
      r.session.live.send(r.session.model.presetCommand(preset, currentParams()))

  /** Starts the current run again with `newSeed`; the parameters stay as they are. */
  def reseed(newSeed: Long): Unit =
    for r <- running do r.session.live.send(Command.Restart(currentParams(), Some(newSeed)))

  /**
   * The parameters of the running session as they are now. A restart rebuilds the run from its
   * initial parameters, so the Lab passes these along to keep the preset and the slider
   * positions the user has chosen.
   */
  private def currentParams(): Map[String, Double] =
    running
      .flatMap(_.loop.snapshot)
      .map(s => s.paramNames.zip(s.paramValues).toMap)
      .getOrElse(Map.empty)

  /** The heatmap renderers of the current session. */
  def heatmaps: Seq[HeatmapRenderer] = running.toSeq.flatMap(_.heatmaps)

  /** The current parameter panel, if any. */
  def panel: Option[ParamPanel] = running.map(_.panel)

  /** Stops the current session's render loop and simulation thread. */
  def close(): Unit =
    running.foreach { r =>
      r.loop.stop()
      r.session.live.stop()
    }
    running = None
    refreshAnimationItem()

  /**
   * Keyboard shortcuts: Space pause/resume, Right single step, R reset, + / - speed, B tool on or
   * off. They are ignored while a stroke is being dragged.
   */
  def installShortcuts(scene: Scene): Unit =
    scene.addEventFilter(KeyEvent.KEY_PRESSED, (e: KeyEvent) => shortcut(e))

  private def shortcut(e: KeyEvent): Unit =
    val typing = Option(e.getTarget).exists(_.isInstanceOf[TextInputControl])
    if !typing then
      for r <- running if !r.paintables.exists(_.stroking) do
        e.getCode match
          case KeyCode.B if r.toolBox.isDefined =>
            r.toolBox.foreach(_.toggle())
            e.consume()
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
  /** The enlargements offered for saved images. */
  val ImageScales: Seq[Int] = Seq(1, 2, 4, 8)

  /** The largest enlargement of a saved image. */
  val MaxImageScale: Int = 8

  private final case class Running(
      session: LabSession,
      loop: RenderLoop,
      panel: ParamPanel,
      controls: RunControls,
      heatmaps: Seq[HeatmapRenderer],
      toolBox: Option[ToolBox],
      paintables: Seq[Paintable]
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
    case v: View.Heatmap =>
      val r = new HeatmapRenderer(v)
      (r.node, Seq(r))
    case View.Layers(base, dots) =>
      val r = new HeatmapRenderer(base, dots = Some(dots))
      (r.node, Seq(r))
    case v: View.Particles =>
      val r = new ParticlesRenderer(v)
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
