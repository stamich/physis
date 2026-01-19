package physis.core.param

import scala.collection.immutable.VectorMap
import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

/** How a parameter is best explored in a UI slider or a sweep. */
enum Scale:
  case Linear, Log

/**
 * Description of one model parameter.
 *
 * Values travel as doubles (CLI, manifests, UI); [[ParamCodec]] converts them to the field type.
 *
 * @param name    field name in the parameter case class
 * @param default value used when nothing else is given
 * @param min     smallest allowed value (inclusive)
 * @param max     largest allowed value (inclusive)
 * @param unit    unit label for display, e.g. "m", "deg", "1"
 * @param scale   preferred scale for exploration
 * @param doc     one-line description
 */
final case class ParamSpec(
    name: String,
    default: Double,
    min: Double = Double.NegativeInfinity,
    max: Double = Double.PositiveInfinity,
    unit: String = "1",
    scale: Scale = Scale.Linear,
    doc: String = ""
):
  require(name.nonEmpty, "parameter name must not be empty")
  require(min <= max, s"parameter '$name': min ($min) > max ($max)")
  require(
    default >= min && default <= max,
    s"parameter '$name': default ($default) outside [$min, $max]"
  )

/** Converts a field type to and from the double representation used everywhere else. */
trait ParamCodec[A]:
  def encode(value: A): Double
  def decode(value: Double): Either[String, A]

object ParamCodec:
  given ParamCodec[Double] with
    def encode(value: Double): Double = value
    def decode(value: Double): Either[String, Double] = Right(value)

  given ParamCodec[Int] with
    def encode(value: Int): Double = value.toDouble
    def decode(value: Double): Either[String, Int] =
      if value.isWhole && math.abs(value) <= Int.MaxValue.toDouble then Right(value.toInt)
      else Left(s"$value is not an integer")

  given ParamCodec[Boolean] with
    def encode(value: Boolean): Double = if value then 1.0 else 0.0
    def decode(value: Double): Either[String, Boolean] =
      if value == 1.0 then Right(true)
      else if value == 0.0 then Right(false)
      else Left(s"$value is not a boolean (use 0 or 1)")

/**
 * Parameter schema of a model: one [[ParamSpec]] per field of the parameter case class.
 *
 * The schema is the single source for defaults, ranges and documentation. CLI overrides, run
 * manifests and (from 0.2) UI panels are all derived from it.
 */
trait ParamSchema[P]:
  /** Specs in field order. */
  def specs: IndexedSeq[ParamSpec]

  /** Parameter values built from every spec's default. */
  def defaults: P

  /** Field values as doubles, in field order. */
  def toValues(p: P): IndexedSeq[Double]

  /**
   * Builds parameters from named values. Missing names take their default; unknown names,
   * out-of-range values and values the field type cannot hold are rejected.
   */
  def fromValues(values: Map[String, Double]): Either[String, P]

  final def names: IndexedSeq[String] = specs.map(_.name)

  /** Named values in field order. */
  final def toMap(p: P): VectorMap[String, Double] = VectorMap.from(names.zip(toValues(p)))

  /** `base` with some values replaced. */
  final def withOverrides(base: P, overrides: Map[String, Double]): Either[String, P] =
    fromValues(toMap(base) ++ overrides)

object ParamSchema:
  def apply[P](using schema: ParamSchema[P]): ParamSchema[P] = schema

  /** Schema of a model without parameters. */
  val unit: ParamSchema[Unit] = new ParamSchema[Unit]:
    val specs: IndexedSeq[ParamSpec] = Vector.empty
    val defaults: Unit = ()
    def toValues(p: Unit): IndexedSeq[Double] = Vector.empty
    def fromValues(values: Map[String, Double]): Either[String, Unit] =
      if values.isEmpty then Right(())
      else Left(s"unknown parameter(s): ${values.keys.toList.sorted.mkString(", ")}")

  /**
   * Schema for the case class `P`, from one spec per field.
   *
   * Field names and types come from the compiler (`Mirror`); every field must have exactly one
   * spec with the same name, and every field type must have a [[ParamCodec]] (Double, Int,
   * Boolean). A mismatch fails when the schema is created, i.e. the first time the model's
   * companion is used, which every model test exercises.
   *
   * {{{
   * final case class LorenzParams(sigma: Double, rho: Double, beta: Double)
   * object LorenzParams:
   *   given schema: ParamSchema[LorenzParams] = ParamSchema.derived[LorenzParams](
   *     ParamSpec("sigma", default = 10.0, min = 0.0, max = 100.0, doc = "Prandtl number"),
   *     ParamSpec("rho", default = 28.0, min = 0.0, max = 500.0, doc = "Rayleigh number"),
   *     ParamSpec("beta", default = 8.0 / 3.0, min = 0.0, max = 20.0)
   *   )
   * }}}
   */
  inline def derived[P <: Product](specs: ParamSpec*)(using m: Mirror.ProductOf[P]): ParamSchema[P] =
    val labels = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    val codecs = summonAll[Tuple.Map[m.MirroredElemTypes, ParamCodec]].toList
      .map(_.asInstanceOf[ParamCodec[Any]])
    fromMirror[P](labels.toVector, codecs.toVector, specs.toVector, m)

  /**
   * Builds a schema from field labels and codecs extracted by [[derived]]. Public because inline
   * code expands at call sites outside this package; prefer [[derived]].
   */
  def fromMirror[P <: Product](
      labels: Vector[String],
      codecs: Vector[ParamCodec[Any]],
      specs: Vector[ParamSpec],
      mirror: Mirror.ProductOf[P]
  ): ParamSchema[P] =
    new MirrorSchema[P](labels, codecs, specs, mirror)

/** Schema implementation backed by a product `Mirror`. */
private final class MirrorSchema[P <: Product](
    labels: Vector[String],
    codecs: Vector[ParamCodec[Any]],
    declared: Vector[ParamSpec],
    mirror: Mirror.ProductOf[P]
) extends ParamSchema[P]:

  private val declaredNames = declared.map(_.name)
  private val duplicates = declaredNames.diff(declaredNames.distinct).distinct
  require(duplicates.isEmpty, s"duplicate ParamSpec(s): ${duplicates.mkString(", ")}")

  private val byName = declared.map(s => s.name -> s).toMap
  private val missing = labels.filterNot(byName.contains)
  require(missing.isEmpty, s"no ParamSpec for field(s): ${missing.mkString(", ")}")

  private val extra = declaredNames.filterNot(labels.contains)
  require(extra.isEmpty, s"ParamSpec for unknown field(s): ${extra.mkString(", ")}")

  val specs: IndexedSeq[ParamSpec] = labels.map(byName)

  val defaults: P = build(specs.map(_.default)) match
    case Right(p) => p
    case Left(error) => throw new IllegalArgumentException(s"invalid defaults: $error")

  def toValues(p: P): IndexedSeq[Double] =
    p.productIterator.zip(codecs).map((value, codec) => codec.encode(value)).toVector

  def fromValues(values: Map[String, Double]): Either[String, P] =
    val unknown = values.keySet.diff(labels.toSet)
    if unknown.nonEmpty then Left(s"unknown parameter(s): ${unknown.toList.sorted.mkString(", ")}")
    else build(specs.map(s => values.getOrElse(s.name, s.default)))

  private def build(raw: IndexedSeq[Double]): Either[String, P] =
    val decoded = specs.indices.map { i =>
      val spec = specs(i)
      val value = raw(i)
      if value.isNaN || value < spec.min || value > spec.max then
        Left(s"parameter '${spec.name}' = $value outside [${spec.min}, ${spec.max}]")
      else codecs(i).decode(value).left.map(e => s"parameter '${spec.name}': $e")
    }
    decoded.collectFirst { case Left(error) => error } match
      case Some(error) => Left(error)
      case None =>
        val fields = decoded.collect { case Right(v) => v.asInstanceOf[Object] }.toArray
        Right(mirror.fromProduct(Tuple.fromArray(fields)))
