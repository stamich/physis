package physis.fx

import java.util.Locale

import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.{
  CheckBox,
  Label,
  Slider,
  Spinner,
  SpinnerValueFactory,
  TextField,
  Tooltip
}
import javafx.scene.layout.{ColumnConstraints, GridPane, HBox, Priority}

import physis.core.live.Snapshot
import physis.core.param.ParamSchema

/**
 * Parameter controls generated from a model's schema, one row per parameter (see
 * [[ControlSpec]]). Every accepted edit calls `onChange(name, value)`; invalid text is marked
 * red and not sent. [[refresh]] shows values from the run (e.g. after a reset) without echoing
 * them back.
 */
final class ParamPanel(
    schema: ParamSchema[?],
    initial: Map[String, Double],
    onChange: (String, Double) => Unit
):
  val specs: Vector[ControlSpec] = ControlSpec.of(schema)

  private val grid = new GridPane()
  grid.setHgap(8)
  grid.setVgap(6)
  grid.setPadding(new Insets(8))
  grid.getColumnConstraints.addAll(
    new ColumnConstraints(), {
      val c = new ColumnConstraints()
      c.setHgrow(Priority.ALWAYS)
      c
    }
  )

  /** The panel's node. */
  val node: Node = grid

  private var updating = false
  private val rows: Vector[Row] = specs.zipWithIndex.map((spec, i) => row(spec, i))

  /** The control nodes of parameter `name`, for tests and shortcuts. */
  def controls(name: String): Seq[Node] = rows.find(_.spec.name == name).map(_.nodes).getOrElse(Nil)

  /** Shows the run's current values; rows being edited keep what the user typed. */
  def refresh(snapshot: Snapshot): Unit =
    updating = true
    try rows.foreach(r => r.show(snapshot.param(r.spec.name)))
    finally updating = false

  private def send(name: String, value: Double): Unit = if !updating then onChange(name, value)

  private def row(c: ControlSpec, index: Int): Row =
    val label = new Label(c.label)
    if c.spec.doc.nonEmpty then label.setTooltip(new Tooltip(c.spec.doc))
    val start = initial.getOrElse(c.name, c.spec.default)
    val r = c.control match
      case ControlKind.Slider(min, max, _) => sliderRow(c, min, max, start)
      case ControlKind.NumberField => fieldRow(c, start)
      case ControlKind.Spinner(min, max) => spinnerRow(c, min, max, start)
      case ControlKind.CheckBox => checkRow(c, start)
    grid.add(label, 0, index)
    grid.add(r.container, 1, index)
    r

  private abstract class Row(val spec: ControlSpec):
    def container: Node
    def nodes: Seq[Node]
    def show(value: Double): Unit

  private def format(v: Double): String =
    if v.isWhole && math.abs(v) < 1e15 then "%.0f".formatLocal(Locale.ROOT, v)
    else "%.6g".formatLocal(Locale.ROOT, v).replaceAll("\\.?0+(?=e|$)", "")

  private def textField(c: ControlSpec, value: Double, accept: Double => Unit): TextField =
    val field = new TextField(format(value))
    field.setPrefColumnCount(7)
    def commit(): Unit = c.parse(field.getText) match
      case Right(v) =>
        field.setStyle("")
        field.setTooltip(null)
        accept(v)
      case Left(error) =>
        field.setStyle("-fx-border-color: #e15759; -fx-border-width: 1.5;")
        field.setTooltip(new Tooltip(error))
    field.setOnAction(_ => commit())
    Fx.onChange(field.focusedProperty)(focused => if !focused.booleanValue then commit())
    field

  private def sliderRow(c: ControlSpec, min: Double, max: Double, start: Double): Row =
    val slider = new Slider(c.toSlider(min), c.toSlider(max), c.toSlider(start))
    slider.setMaxWidth(Double.MaxValue)
    HBox.setHgrow(slider, Priority.ALWAYS)
    def typed(v: Double): Unit =
      updating = true
      try slider.setValue(c.toSlider(v))
      finally updating = false
      send(c.name, v)
    val field = textField(c, start, typed)
    def slid(position: Number): Unit =
      if !updating then
        val v = c.fromSlider(position.doubleValue)
        field.setText(format(v))
        field.setStyle("")
        send(c.name, v)
    Fx.onChange(slider.valueProperty)(slid)
    new Row(c):
      val container: Node = new HBox(6, slider, field)
      def nodes: Seq[Node] = Seq(slider, field)
      def show(value: Double): Unit =
        if !value.isNaN && !field.isFocused && !slider.isValueChanging then
          slider.setValue(c.toSlider(value))
          field.setText(format(value))

  private def fieldRow(c: ControlSpec, start: Double): Row =
    val field = textField(c, start, v => send(c.name, v))
    new Row(c):
      val container: Node = field
      def nodes: Seq[Node] = Seq(field)
      def show(value: Double): Unit =
        if !value.isNaN && !field.isFocused then field.setText(format(value))

  private def spinnerRow(c: ControlSpec, min: Int, max: Int, start: Double): Row =
    val spinner = new Spinner[Integer]()
    spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
      min,
      max,
      start.toInt
    ))
    spinner.setEditable(true)
    Fx.onChange(spinner.valueProperty)(v => if v != null then send(c.name, v.doubleValue))
    new Row(c):
      val container: Node = spinner
      def nodes: Seq[Node] = Seq(spinner)
      def show(value: Double): Unit =
        if !value.isNaN && !spinner.isFocused then
          spinner.getValueFactory.setValue(Integer.valueOf(value.toInt))

  private def checkRow(c: ControlSpec, start: Double): Row =
    val box = new CheckBox()
    box.setSelected(start == 1.0)
    Fx.onChange(box.selectedProperty)(on => send(c.name, if on.booleanValue then 1.0 else 0.0))
    new Row(c):
      val container: Node = box
      def nodes: Seq[Node] = Seq(box)
      def show(value: Double): Unit = if !value.isNaN then box.setSelected(value == 1.0)
