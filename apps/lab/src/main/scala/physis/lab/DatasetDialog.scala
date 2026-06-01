package physis.lab

import javafx.geometry.Insets
import javafx.scene.control.{ButtonType, CheckBox, Dialog, Label, Spinner}
import javafx.scene.layout.GridPane
import javafx.stage.Window

import physis.core.run.{Manifest, ModelRegistry}

/**
 * The options of File → Export data as a form: how many steps between records, the precision
 * of the fields and the manifest. Under the fields a line says what the file will be (records,
 * size, time), or why it cannot be made. Runs on the FX thread.
 *
 * @param stepsPerSecond how fast the session ran, for the time estimate; 0 if not known
 */
final class DatasetForm(
    model: LabModel,
    manifest: Manifest,
    registry: ModelRegistry,
    stepsPerSecond: Double,
    initial: DatasetOptions
):
  val every: Spinner[Integer] =
    val s = new Spinner[Integer](1, 1_000_000_000, math.min(initial.every, 1_000_000_000L).toInt)
    s.setEditable(true)
    s.setPrefWidth(120)
    s

  val single: CheckBox = new CheckBox("Fields as 32-bit floats (half the size)")
  single.setSelected(initial.single)
  val writeManifest: CheckBox = new CheckBox("Write the manifest next to the file")
  writeManifest.setSelected(initial.writeManifest)

  /** What the file will be, or what is wrong. */
  val summary: Label = new Label("")
  summary.setWrapText(true)
  summary.setPrefWidth(380)

  private var listeners: Vector[Boolean => Unit] = Vector.empty

  /** Calls `f` with whether the options can be used, now and whenever they change. */
  def onValid(f: Boolean => Unit): Unit =
    listeners = listeners :+ f
    f(estimate.isRight)

  /** The options as the fields stand, including text typed and not yet committed. */
  def options: DatasetOptions =
    DatasetOptions(
      every = every.getEditor.getText.trim.toLongOption
        .orElse(Option(every.getValue).map(_.longValue))
        .getOrElse(initial.every),
      single = single.isSelected,
      writeManifest = writeManifest.isSelected
    )

  private def estimate: Either[String, LabDatasets.Estimate] =
    LabDatasets.estimate(model, manifest, registry, stepsPerSecond, options)

  /** Writes the line under the fields and tells the listeners. */
  def refresh(): Unit =
    val e = estimate
    summary.setText(e.fold(identity, _.text))
    listeners.foreach(_(e.isRight))

  val node: GridPane =
    val g = new GridPane()
    g.setHgap(10)
    g.setVgap(8)
    g.setPadding(new Insets(12))
    g.add(new Label("Record every"), 0, 0)
    g.add(every, 1, 0)
    g.add(new Label("steps"), 2, 0)
    g.add(single, 0, 1, 3, 1)
    g.add(writeManifest, 0, 2, 3, 1)
    g.add(new Label(f"The session so far: ${manifest.steps.getOrElse(0L)} steps."), 0, 3, 3, 1)
    g.add(summary, 0, 4, 3, 1)
    g

  every.valueProperty.addListener((_, _, _) => refresh())
  every.getEditor.textProperty.addListener((_, _, _) => refresh())
  single.selectedProperty.addListener((_, _, _) => refresh())
  refresh()

/** The dialog around a [[DatasetForm]]. */
object DatasetDialog:
  /** Shows the form with OK and Cancel; the options if OK was pressed. */
  def ask(owner: Window, form: DatasetForm): Option[DatasetOptions] =
    val dialog = new Dialog[ButtonType]()
    dialog.setTitle("Export data")
    dialog.setHeaderText("Replay the session and save its fields and probes as a NetCDF file")
    Option(owner).foreach(dialog.initOwner(_))
    dialog.getDialogPane.setContent(form.node)
    dialog.getDialogPane.getButtonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
    val ok = dialog.getDialogPane.lookupButton(ButtonType.OK)
    form.onValid(valid => ok.setDisable(!valid))
    val answer = dialog.showAndWait()
    if answer.isPresent && answer.get == ButtonType.OK then Some(form.options) else None
