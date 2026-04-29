package physis.lab

import javafx.geometry.Insets
import javafx.scene.control.{ButtonType, CheckBox, Dialog, Label, Spinner}
import javafx.scene.layout.{ColumnConstraints, GridPane, Priority}
import javafx.stage.Window

import physis.core.live.Snapshot

/**
 * The options of File → Save animation as a form: how many steps between pictures, how fast it
 * plays, how often, the size of a cell, the colour scale and the manifest. Under the fields a
 * line says what the file will be (frames, pixels, size, time), or why it cannot be made.
 *
 * `steps` is the length of the run as the window shows it; the file will be as long as the
 * session is when the file name has been chosen. Runs on the FX thread.
 *
 * @param stepsPerSecond how fast the session ran, for the time estimate; 0 if not known
 */
final class AnimationForm(
    session: LabSession,
    snapshot: Snapshot,
    steps: Long,
    stepsPerSecond: Double,
    initial: AnimationOptions
):
  private def spinner(min: Int, max: Int, value: Int): Spinner[Integer] =
    val s = new Spinner[Integer](min, max, value)
    s.setEditable(true)
    s.setPrefWidth(120)
    s

  val every: Spinner[Integer] =
    spinner(1, 1_000_000_000, math.min(initial.every, 1_000_000_000L).toInt)
  val fps: Spinner[Integer] = spinner(1, 65535, initial.fps)
  val scale: Spinner[Integer] = spinner(1, 16, initial.scale)
  val loops: Spinner[Integer] = spinner(0, 65535, initial.loops)
  val colorBar: CheckBox = new CheckBox("Colour scale at the right")
  colorBar.setSelected(initial.colorBar)
  val manifest: CheckBox = new CheckBox("Write the manifest next to the file")
  manifest.setSelected(initial.writeManifest)

  /** What the file will be, or what is wrong. */
  val summary: Label = new Label("")
  summary.setWrapText(true)
  summary.setPrefWidth(380)

  private var listeners: Vector[Boolean => Unit] = Vector.empty

  /** Calls `f` with whether the options can be used, now and whenever they change. */
  def onValid(f: Boolean => Unit): Unit =
    listeners = listeners :+ f
    f(problem.isEmpty)

  /** The value of a spinner, including text typed and not yet committed. */
  private def value(s: Spinner[Integer], fallback: Int): Int =
    s.getEditor.getText.trim.toIntOption.getOrElse(Option(s.getValue).fold(fallback)(_.intValue))

  /** The options as the fields stand. */
  def options: AnimationOptions =
    AnimationOptions(
      every = value(every, initial.every.toInt).toLong,
      fps = value(fps, initial.fps),
      scale = value(scale, initial.scale),
      loops = value(loops, initial.loops),
      colorBar = colorBar.isSelected,
      writeManifest = manifest.isSelected
    )

  private def checked: Either[String, AnimationOptions] =
    val o = options
    LabAnimations.check(steps, o) match
      case Some(why) => Left(why)
      case None => scala.util.Try(o.frames).toEither.left.map(_.getMessage).map(_ => o)

  /** Why the options cannot be used, if they cannot. */
  def problem: Option[String] = checked.left.toOption

  /** Writes the line under the fields and tells the listeners. */
  def refresh(): Unit =
    val text = checked match
      case Left(why) => why
      case Right(o) =>
        LabAnimations
          .estimate(session, snapshot, steps, stepsPerSecond, o)
          .fold(identity, _.text)
    summary.setText(text)
    listeners.foreach(_(problem.isEmpty))

  val node: GridPane =
    val g = new GridPane()
    g.setHgap(10)
    g.setVgap(8)
    g.setPadding(new Insets(12))
    val label = new ColumnConstraints()
    label.setHgrow(Priority.SOMETIMES)
    g.getColumnConstraints.add(label)
    def row(i: Int, text: String, control: javafx.scene.Node, hint: String): Unit =
      g.add(new Label(text), 0, i)
      g.add(control, 1, i)
      g.add(new Label(hint), 2, i)
    row(0, "Picture every", every, "steps")
    row(1, "Plays at", fps, "pictures per second")
    row(2, "Cell size", scale, "pixels")
    row(3, "Plays", loops, "times (0: for ever)")
    g.add(colorBar, 0, 4, 3, 1)
    g.add(manifest, 0, 5, 3, 1)
    g.add(new Label(f"The session so far: $steps steps."), 0, 6, 3, 1)
    g.add(summary, 0, 7, 3, 1)
    g

  for s <- Seq(every, fps, scale, loops) do
    s.valueProperty.addListener((_, _, _) => refresh())
    s.getEditor.textProperty.addListener((_, _, _) => refresh())
  colorBar.selectedProperty.addListener((_, _, _) => refresh())
  refresh()

/** The dialog around an [[AnimationForm]]. */
object AnimationDialog:
  /** Shows the form with OK and Cancel; the options if OK was pressed. */
  def ask(owner: Window, form: AnimationForm): Option[AnimationOptions] =
    val dialog = new Dialog[ButtonType]()
    dialog.setTitle("Save animation")
    dialog.setHeaderText("Replay the session and save it as an animated PNG")
    Option(owner).foreach(dialog.initOwner(_))
    dialog.getDialogPane.setContent(form.node)
    dialog.getDialogPane.getButtonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
    val ok = dialog.getDialogPane.lookupButton(ButtonType.OK)
    form.onValid(valid => ok.setDisable(!valid))
    val answer = dialog.showAndWait()
    if answer.isPresent && answer.get == ButtonType.OK then Some(form.options) else None
