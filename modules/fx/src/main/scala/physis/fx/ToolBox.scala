package physis.fx

import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.{Label, Slider, TextField, ToggleButton, ToggleGroup, Tooltip}
import javafx.scene.layout.{FlowPane, HBox, Priority, VBox}

/**
 * A tool of the Lab's tool box: a name and the ranges of its radius and its value.
 *
 * @param valueLabel   name of the tool's value (for example "strength"); none if it has no value
 * @param valueDefault the value a tool starts with
 * @param valueRange   smallest and largest value
 * @param radiusRange  smallest and largest radius, in the units of the field the tool paints on
 * @param radiusDefault the radius the tool starts with
 */
final case class ToolSpec(
    name: String,
    valueLabel: Option[String] = None,
    valueDefault: Double = 1.0,
    valueRange: (Double, Double) = (0.0, 1.0),
    radiusRange: (Double, Double) = (1.0, 64.0),
    radiusDefault: Double = 8.0
)

/**
 * The tools of a model as buttons (with an Off button, selected at first, so the pointer only
 * reads cells until a tool is picked), a radius slider, a value field and a counter of the
 * interventions recorded so far.
 *
 * `changed` is called whenever the tool, the radius or the value changes.
 */
final class ToolBox(val tools: Vector[ToolSpec], changed: () => Unit = () => ()):
  require(tools.nonEmpty, "a tool box needs a tool")

  private val group = new ToggleGroup()
  val off: ToggleButton = new ToggleButton("Off")
  val buttons: Vector[ToggleButton] = tools.map(t => new ToggleButton(t.name))
  off.setToggleGroup(group)
  buttons.foreach(_.setToggleGroup(group))
  off.setSelected(true)
  off.setTooltip(new Tooltip("No tool: the pointer only reads values (B toggles)"))

  val radiusSlider: Slider = new Slider(1.0, 64.0, 8.0)
  private val radiusLabel = new Label("")
  val valueField: TextField = new TextField("")
  valueField.setPrefColumnCount(6)
  private val valueName = new Label("")
  val counter: Label = new Label("0 strokes recorded")

  private var last: ToolSpec = tools.head
  private var valueNow = tools.head.valueDefault
  private var quiet = false

  private def fire(): Unit = if !quiet then changed()

  buttons.zip(tools).foreach { (b, t) =>
    b.setOnAction(_ => if b.isSelected then pick(t))
  }
  off.setOnAction(_ =>
    off.setSelected(true)
    fire()
  )

  radiusSlider.setTooltip(new Tooltip("Brush radius (mouse wheel over the field)"))
  Fx.onChange(radiusSlider.valueProperty) { v =>
    radiusLabel.setText(f"${v.doubleValue}%.0f")
    fire()
  }
  valueField.setOnAction(_ => commitValue())
  Fx.onChange(valueField.focusedProperty) { focused =>
    if !focused then commitValue()
  }

  /** The tool that is on, if any. */
  def selected: Option[ToolSpec] =
    buttons.zip(tools).collectFirst { case (b, t) if b.isSelected => t }

  /** Brush radius. */
  def radius: Double = radiusSlider.getValue

  /** The value of the selected tool; the last valid number typed, or its default. */
  def value: Double = valueNow

  /** Chooses the tool called `name`, or none for `None`, as if it had been clicked. */
  def select(name: Option[String]): Unit =
    name.flatMap(n => tools.find(_.name == n)) match
      case Some(t) => pick(t)
      case None =>
        off.setSelected(true)
        fire()

  /** B: switches the tool off, or on again with the one used last. */
  def toggle(): Unit =
    if selected.isDefined then select(None) else pick(last)

  /** Sets the radius, within the selected tool's range. */
  def setRadius(r: Double): Unit =
    radiusSlider.setValue(math.max(radiusSlider.getMin, math.min(radiusSlider.getMax, r)))

  /** Shows how many interventions the session has recorded. */
  def setStrokes(n: Int): Unit =
    counter.setText(if n == 1 then "1 stroke recorded" else s"$n strokes recorded")

  private def pick(t: ToolSpec): Unit =
    last = t
    quiet = true
    try
      buttons(tools.indexOf(t)).setSelected(true)
      radiusSlider.setMin(t.radiusRange._1)
      radiusSlider.setMax(t.radiusRange._2)
      radiusSlider.setValue(math.max(t.radiusRange._1, math.min(t.radiusRange._2, t.radiusDefault)))
      valueNow = t.valueDefault
      valueField.setText(ToolBox.format(valueNow))
      valueField.setStyle("")
      valueName.setText(t.valueLabel.getOrElse(""))
      valueField.setDisable(t.valueLabel.isEmpty)
    finally quiet = false
    fire()

  private def commitValue(): Unit =
    for t <- selected do
      valueField.getText.trim.toDoubleOption.filter(v =>
        v >= t.valueRange._1 && v <= t.valueRange._2
      ) match
        case Some(v) =>
          valueNow = v
          valueField.setStyle("")
          fire()
        case None =>
          valueField.setStyle("-fx-border-color: #e15759; -fx-border-width: 1.5;")

  /** The box's node. */
  val node: Node =
    val toolRow = new FlowPane(6, 6)
    toolRow.getChildren.add(off)
    buttons.foreach(b => toolRow.getChildren.add(b))
    val radiusRow = new HBox(6, new Label("Radius"), radiusSlider, radiusLabel)
    radiusRow.setAlignment(Pos.CENTER_LEFT)
    HBox.setHgrow(radiusSlider, Priority.ALWAYS)
    val valueRow = new HBox(6, valueName, valueField)
    valueRow.setAlignment(Pos.CENTER_LEFT)
    val box = new VBox(6, new Label("Tools"), toolRow, radiusRow, valueRow, counter)
    box.setPadding(new Insets(4, 0, 4, 0))
    radiusLabel.setText(f"${radiusSlider.getValue}%.0f")
    valueField.setDisable(true)
    box

object ToolBox:
  private def format(v: Double): String =
    if v.isWhole && math.abs(v) < 1e9 then v.toLong.toString else v.toString
