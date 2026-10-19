package physis.core.run

/**
 * A disc in cell coordinates: centre `(x, y)` and radius `r`, all in cells and all fractional,
 * so a brush can sit between cells. A cell belongs to the disc when its centre is within `r`
 * of `(x, y)`; on periodic axes distances wrap.
 */
final case class Disc(x: Double, y: Double, r: Double)

/** What a brush does to the cells it covers. */
enum Op:
  /** Sets the cells to the brush value. */
  case Assign

  /** Adds the brush value to the cells. */
  case Add

  /** Sets the cells to zero; the brush value is ignored. */
  case Erase

  def name: String = this match
    case Assign => "set"
    case Add => "add"
    case Erase => "erase"

object Op:
  def parse(text: String): Either[String, Op] = text match
    case "set" => Right(Op.Assign)
    case "add" => Right(Op.Add)
    case "erase" => Right(Op.Erase)
    case other => Left(s"unknown brush operation '$other'")

/**
 * An edit to the state of a running model. Like a [[ParamEvent]] it happens between two steps
 * and carries everything needed to repeat it; it stores a shape and an operation, never cells,
 * so a stroke takes tens of bytes in a manifest and replays through pure arithmetic.
 *
 * Models decide what the edits mean through [[physis.core.Model.intervene]].
 */
enum Intervention:
  /** Applies `op` with `value` to the cells of the field slice `target` inside `disc`. */
  case Brush(target: String, disc: Disc, op: Op, value: Double)

  /** Adds a Gaussian bump of the given amplitude and standard deviation to a field slice. */
  case Pulse(target: String, x: Double, y: Double, sigma: Double, amplitude: Double)

  /** Turns the agents inside `disc` away from its centre by `strength` radians. */
  case Disturb(disc: Disc, strength: Double)

  /** Describes what is wrong with this intervention, if anything. */
  def validate: Option[String] = this match
    case Brush(target, disc, _, value) =>
      checkTarget(target).orElse(checkDisc(disc)).orElse(checkFinite("value", value))
    case Pulse(target, x, y, sigma, amplitude) =>
      checkTarget(target)
        .orElse(checkFinite("x", x))
        .orElse(checkFinite("y", y))
        .orElse(
          if sigma > 0.0 && sigma <= Intervention.MaxExtent then None
          else Some(s"pulse sigma must be in (0, ${Intervention.MaxExtent}], got $sigma")
        )
        .orElse(checkFinite("amplitude", amplitude))
    case Disturb(disc, strength) =>
      checkDisc(disc).orElse(checkFinite("strength", strength))

  private def checkTarget(target: String): Option[String] =
    if target.nonEmpty then None else Some("intervention target must not be empty")

  private def checkDisc(d: Disc): Option[String] =
    checkFinite("x", d.x)
      .orElse(checkFinite("y", d.y))
      .orElse(
        if d.r > 0.0 && d.r <= Intervention.MaxExtent then None
        else Some(s"radius must be in (0, ${Intervention.MaxExtent}], got ${d.r}")
      )

  private def checkFinite(name: String, v: Double): Option[String] =
    if v.isNaN || v.isInfinite then Some(s"$name must be finite, got $v") else None

object Intervention:
  /** Largest radius or sigma accepted, in cells. */
  val MaxExtent: Double = 4096.0

  private[run] def toJson(iv: Intervention): Json =
    iv match
      case Brush(target, d, op, value) =>
        Json.JObj(
          Vector(
            "kind" -> Json.JStr("brush"),
            "target" -> Json.JStr(target),
            "x" -> Json.num(d.x),
            "y" -> Json.num(d.y),
            "r" -> Json.num(d.r),
            "op" -> Json.JStr(op.name),
            "value" -> Json.num(value)
          )
        )
      case Pulse(target, x, y, sigma, amplitude) =>
        Json.JObj(
          Vector(
            "kind" -> Json.JStr("pulse"),
            "target" -> Json.JStr(target),
            "x" -> Json.num(x),
            "y" -> Json.num(y),
            "sigma" -> Json.num(sigma),
            "amplitude" -> Json.num(amplitude)
          )
        )
      case Disturb(d, strength) =>
        Json.JObj(
          Vector(
            "kind" -> Json.JStr("disturb"),
            "x" -> Json.num(d.x),
            "y" -> Json.num(d.y),
            "r" -> Json.num(d.r),
            "strength" -> Json.num(strength)
          )
        )

  private[run] def fromJson(json: Json): Either[String, Intervention] =
    val parsed = json.field("kind").flatMap(_.asString).flatMap {
      case "brush" =>
        for
          target <- json.field("target").flatMap(_.asString)
          x <- json.field("x").flatMap(_.asDouble)
          y <- json.field("y").flatMap(_.asDouble)
          r <- json.field("r").flatMap(_.asDouble)
          op <- json.field("op").flatMap(_.asString).flatMap(Op.parse)
          value <- json.field("value").flatMap(_.asDouble)
        yield Brush(target, Disc(x, y, r), op, value)
      case "pulse" =>
        for
          target <- json.field("target").flatMap(_.asString)
          x <- json.field("x").flatMap(_.asDouble)
          y <- json.field("y").flatMap(_.asDouble)
          sigma <- json.field("sigma").flatMap(_.asDouble)
          amplitude <- json.field("amplitude").flatMap(_.asDouble)
        yield Pulse(target, x, y, sigma, amplitude)
      case "disturb" =>
        for
          x <- json.field("x").flatMap(_.asDouble)
          y <- json.field("y").flatMap(_.asDouble)
          r <- json.field("r").flatMap(_.asDouble)
          strength <- json.field("strength").flatMap(_.asDouble)
        yield Disturb(Disc(x, y, r), strength)
      case other => Left(s"unknown intervention kind '$other'")
    }
    parsed.flatMap(iv => iv.validate.toLeft(iv))

/**
 * An intervention applied immediately before step `step` (steps count from 0). Runs record
 * every intervention they apply; replay applies the same ones before the same steps, after the
 * parameter changes of that step and in the order they were made.
 */
final case class InterventionEvent(step: Long, intervention: Intervention)
