package physis.core.sweep

import scala.collection.immutable.VectorMap

import physis.core.param.ParamSchema

/** One parameter of a sweep and the values it takes, in the order they are visited. */
final case class Axis(name: String, values: Vector[Double]):
  require(name.nonEmpty, "axis name must not be empty")
  require(values.nonEmpty, s"axis '$name' has no values")

/**
 * One run of a sweep.
 *
 * @param index  position in the grid, from 0
 * @param values the parameters this point sets, in axis order; all others keep their default
 * @param seed   the run seed, when the grid has a `seed` axis
 */
final case class GridPoint(index: Int, values: VectorMap[String, Double], seed: Option[Long])

/**
 * The points of a parameter sweep: the product of its axes. The first axis changes slowest, so
 * the order of the points is fixed by the order of the axes and never by who computes them.
 *
 * Axes are written as text, one per parameter (see [[ParamGrid.parse]]):
 *
 * {{{
 * F=0.02:0.06:5        five values from 0.02 to 0.06, both included
 * k=log:0.01:1:3       three values spaced evenly in the logarithm: 0.01, 0.1, 1
 * n=100,200,400        a list
 * seed=1..8            the seeds 1 to 8
 * Du=0.16              one value
 * }}}
 */
final class ParamGrid private (val axes: Vector[Axis]):

  /** Number of points. */
  val size: Int = axes.foldLeft(1L)((n, axis) => n * axis.values.size).toInt

  /** The point at position `index`; the last axis changes fastest. */
  def point(index: Int): GridPoint =
    require(index >= 0 && index < size, s"point $index outside 0 until $size")
    var rest = index
    val picked = new Array[Double](axes.size)
    var k = axes.size - 1
    while k >= 0 do
      val n = axes(k).values.size
      picked(k) = axes(k).values(rest % n)
      rest /= n
      k -= 1
    val named = axes.indices.map(j => axes(j).name -> picked(j))
    val (seeds, params) = named.partition(_._1 == ParamGrid.SeedName)
    GridPoint(index, VectorMap.from(params), seeds.headOption.map(_._2.toLong))

  /** All points in order. */
  def points: Vector[GridPoint] = Vector.tabulate(size)(point)

object ParamGrid:
  /** Name of the axis that sets the run seed instead of a parameter. */
  val SeedName: String = "seed"

  /** Largest grid accepted; a sweep of more runs is almost certainly a typing mistake. */
  val MaxPoints: Int = 100000

  /** A grid with no axes: one point with default parameters. */
  val single: ParamGrid = new ParamGrid(Vector.empty)

  /**
   * Parses one text per axis and checks every value against `schema`: the name must be a
   * parameter (or `seed`), the value inside its range and of the field's kind.
   */
  def parse(specs: Seq[String], schema: ParamSchema[?]): Either[String, ParamGrid] =
    val axes = specs.foldLeft[Either[String, Vector[Axis]]](Right(Vector.empty)) { (acc, text) =>
      for
        done <- acc
        axis <- parseAxis(text)
        _ <- Either.cond(!done.exists(_.name == axis.name), (), s"axis '${axis.name}' given twice")
        _ <- check(axis, schema)
      yield done :+ axis
    }
    axes.flatMap { all =>
      val total = all.foldLeft(1L)((n, axis) => n * axis.values.size)
      if total > MaxPoints then Left(s"grid of $total points is larger than $MaxPoints")
      else Right(new ParamGrid(all))
    }

  /** Parses a single axis such as `F=0.02:0.06:5`, without looking at any schema. */
  def parseAxis(text: String): Either[String, Axis] =
    text.split("=", 2) match
      case Array(name, rhs) if name.nonEmpty && rhs.nonEmpty =>
        val values =
          if name == SeedName && rhs.contains("..") then seedRange(rhs)
          else if rhs.startsWith("log:") then logSpace(rhs.stripPrefix("log:"))
          else if rhs.contains(":") then linSpace(rhs)
          else list(rhs)
        values.left.map(e => s"axis '$name': $e").map(Axis(name, _))
      case _ => Left(s"an axis is written name=values, got '$text'")

  private def check(axis: Axis, schema: ParamSchema[?]): Either[String, Unit] =
    if axis.name == SeedName then
      axis.values.find(v => !v.isWhole || v < 0.0) match
        case Some(bad) => Left(s"axis 'seed': $bad is not a whole number from 0 up")
        case None => Right(())
    else
      axis.values.iterator
        .map(v => schema.fromValues(Map(axis.name -> v)).left.map(e => s"axis '${axis.name}': $e"))
        .collectFirst { case Left(error) => error }
        .toLeft(())

  private def number(text: String): Either[String, Double] =
    text.trim.toDoubleOption.filter(_.isFinite).toRight(s"'$text' is not a finite number")

  private def count(text: String): Either[String, Int] =
    text.trim.toIntOption
      .filter(n => n >= 2 && n <= MaxPoints)
      .toRight(s"the number of values must be a whole number from 2 to $MaxPoints, got '$text'")

  private def list(text: String): Either[String, Vector[Double]] =
    text.split(",", -1).toVector.foldLeft[Either[String, Vector[Double]]](Right(Vector.empty)) {
      (acc, part) => acc.flatMap(done => number(part).map(done :+ _))
    }

  private def linSpace(text: String): Either[String, Vector[Double]] =
    text.split(":", -1) match
      case Array(a, b, n) =>
        for
          lo <- number(a)
          hi <- number(b)
          m <- count(n)
        yield Vector.tabulate(m)(i => if i == m - 1 then hi else lo + (hi - lo) * i / (m - 1))
      case _ => Left(s"expected a:b:n, got '$text'")

  private def logSpace(text: String): Either[String, Vector[Double]] =
    text.split(":", -1) match
      case Array(a, b, n) =>
        for
          lo <- number(a)
          hi <- number(b)
          m <- count(n)
          _ <- Either.cond(lo > 0.0 && hi > 0.0, (), "a logarithmic axis needs positive ends")
        yield
          val l0 = StrictMath.log(lo)
          val l1 = StrictMath.log(hi)
          Vector.tabulate(m) { i =>
            if i == 0 then lo
            else if i == m - 1 then hi
            else StrictMath.exp(l0 + (l1 - l0) * i / (m - 1))
          }
      case _ => Left(s"expected log:a:b:n, got '$text'")

  private def seedRange(text: String): Either[String, Vector[Double]] =
    text.split("\\.\\.", -1) match
      case Array(a, b) =>
        (a.trim.toLongOption, b.trim.toLongOption) match
          case (Some(lo), Some(hi)) if lo >= 0 && hi >= lo && hi - lo < MaxPoints =>
            Right(Vector.tabulate((hi - lo + 1).toInt)(i => (lo + i).toDouble))
          case _ => Left(s"expected seeds a..b with 0 <= a <= b, got '$text'")
      case _ => Left(s"expected seeds a..b, got '$text'")
