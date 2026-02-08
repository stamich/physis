package physis.verify

/**
 * Reference neighbour search for particles on a periodic rectangle, written for clarity rather
 * than speed: every pair is tested. Grid-based searches are checked against it.
 *
 * The distance is the minimum-image distance: each coordinate difference is wrapped into
 * `[-size / 2, size / 2]`. Two particles are neighbours when `dx * dx + dy * dy <= radius^2`
 * (the boundary counts), and a particle is not its own neighbour. The arithmetic is spelled out
 * in [[displacement]] and [[withinRadius]] so that other implementations can use the same
 * operations and agree bit for bit, including for particles exactly on the radius.
 */
object Neighbours:

  /** Wrapped coordinate difference `to - from` on a period `size`. */
  def displacement(from: Double, to: Double, size: Double): Double =
    val d = to - from
    if d > 0.5 * size then d - size
    else if d < -0.5 * size then d + size
    else d

  /** Whether the minimum-image distance between the two points is at most `radius`. */
  def withinRadius(
      x0: Double,
      y0: Double,
      x1: Double,
      y1: Double,
      width: Double,
      height: Double,
      radius: Double
  ): Boolean =
    val dx = displacement(x0, x1, width)
    val dy = displacement(y0, y1, height)
    dx * dx + dy * dy <= radius * radius

  /** For each of the first `count` particles, the set of its neighbours' indices. */
  def bruteForce(
      x: Array[Double],
      y: Array[Double],
      count: Int,
      width: Double,
      height: Double,
      radius: Double
  ): Vector[Set[Int]] =
    Vector.tabulate(count) { i =>
      (0 until count).filter { j =>
        j != i && withinRadius(x(i), y(i), x(j), y(j), width, height, radius)
      }.toSet
    }

  /**
   * Describes the first difference between two neighbour lists, or `None` if they are equal as
   * sets for every particle. The message names the particle and the missing or extra neighbours.
   */
  def mismatch(expected: IndexedSeq[Set[Int]], actual: IndexedSeq[Set[Int]]): Option[String] =
    if expected.size != actual.size then
      Some(s"particle counts differ: ${expected.size} expected, ${actual.size} found")
    else
      expected.indices.collectFirst {
        case i if expected(i) != actual(i) =>
          val missing = expected(i).diff(actual(i)).toList.sorted
          val extra = actual(i).diff(expected(i)).toList.sorted
          s"particle $i: missing ${missing.mkString("[", ", ", "]")}, " +
            s"extra ${extra.mkString("[", ", ", "]")}"
      }
