package physis.fx

import physis.core.param.{ParamKind, ParamSchema, ParamSpec, Scale}

/** Which control edits a parameter. */
enum ControlKind:
  /** Slider over a finite range, with a text field for exact values; `log` slides over log10. */
  case Slider(min: Double, max: Double, log: Boolean)

  /** Text field only: the range is unbounded. */
  case NumberField

  /** Whole numbers. */
  case Spinner(min: Int, max: Int)

  /** On/off. */
  case CheckBox

/**
 * Toolkit-free description of one row of the parameter panel: the parameter and its control.
 * The schema stays the single source of ranges, units and documentation.
 */
final case class ControlSpec(spec: ParamSpec, kind: ParamKind, control: ControlKind):
  def name: String = spec.name

  /** Label text: name and unit, unless the unit is "1". */
  def label: String =
    if spec.unit == "1" || spec.unit.isEmpty then spec.name else s"${spec.name} [${spec.unit}]"

  /** Slider position of a value (log10 of it for log sliders). */
  def toSlider(value: Double): Double = control match
    case ControlKind.Slider(_, _, true) => math.log10(math.max(value, ControlSpec.LogFloor))
    case _ => value

  /** Value at a slider position, clamped to the spec's range; integers are rounded. */
  def fromSlider(position: Double): Double =
    val v = control match
      case ControlKind.Slider(_, _, true) => math.pow(10, position)
      case _ => position
    val clamped = math.max(spec.min, math.min(spec.max, v))
    if kind == ParamKind.Real then clamped else math.rint(clamped)

  /**
   * Parses user input: a number (`.` or `,` as decimal separator, exponent allowed) inside the
   * spec's range, whole for integers, 0/1 or true/false for flags.
   */
  def parse(text: String): Either[String, Double] =
    val t = text.trim
    val number = t.toLowerCase match
      case "true" if kind == ParamKind.Flag => Some(1.0)
      case "false" if kind == ParamKind.Flag => Some(0.0)
      case other => other.replace(',', '.').toDoubleOption
    number match
      case None => Left(s"'$t' is not a number")
      case Some(v) if v.isNaN => Left("NaN is not allowed")
      case Some(v) if v < spec.min || v > spec.max =>
        Left(s"${spec.name} must be in [${spec.min}, ${spec.max}]")
      case Some(v) if kind == ParamKind.Integer && !v.isWhole => Left(s"${spec.name} must be whole")
      case Some(v) if kind == ParamKind.Flag && v != 0.0 && v != 1.0 =>
        Left(s"${spec.name} is 0 or 1")
      case Some(v) => Right(v)

object ControlSpec:
  /** Lower end of a log slider whose range starts at zero or below. */
  val LogFloor = 1e-12

  /** One control per parameter, in schema order. */
  def of(schema: ParamSchema[?]): Vector[ControlSpec] =
    schema.specs.zip(schema.kinds).map((spec, kind) =>
      ControlSpec(spec, kind, control(spec, kind))
    ).toVector

  /** The control for one parameter. */
  def control(spec: ParamSpec, kind: ParamKind): ControlKind = kind match
    case ParamKind.Flag => ControlKind.CheckBox
    case ParamKind.Integer =>
      def bound(v: Double) =
        math.max(Int.MinValue.toDouble, math.min(Int.MaxValue.toDouble, v)).toInt
      ControlKind.Spinner(bound(spec.min), bound(spec.max))
    case ParamKind.Real =>
      if spec.min.isInfinite || spec.max.isInfinite then ControlKind.NumberField
      else if spec.scale == Scale.Log && spec.max > 0 then
        ControlKind.Slider(math.max(spec.min, LogFloor), spec.max, log = true)
      else ControlKind.Slider(spec.min, spec.max, log = false)
