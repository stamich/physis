package physis.fx

import physis.view.{Camera, Trail}

/** Maps a model point to view coordinates. */
trait Projector:
  def viewX(x: Double, y: Double, z: Double): Double
  def viewY(x: Double, y: Double, z: Double): Double

object Projector:
  /** Orthographic orbit projection through a camera. */
  def of(camera: Camera): Projector = new Projector:
    def viewX(x: Double, y: Double, z: Double): Double = camera.viewX(x, y, z)
    def viewY(x: Double, y: Double, z: Double): Double = camera.viewY(x, y, z)

/**
 * A 2D projection mapping [xMin, xMax] x [yMin, yMax] onto a `width` x `height` view, y up,
 * keeping the aspect ratio when `square`.
 */
final class FlatProjector extends Projector:
  private var sx, sy, ox, oy = 0.0

  /** Sets the mapping. */
  def set(
      xMin: Double,
      xMax: Double,
      yMin: Double,
      yMax: Double,
      width: Double,
      height: Double,
      square: Boolean = false
  ): Unit =
    val kx = width / (xMax - xMin)
    val ky = height / (yMax - yMin)
    sx = if square then math.min(kx, ky) else kx
    sy = if square then math.min(kx, ky) else ky
    ox = width / 2 - sx * (xMin + xMax) / 2
    oy = height / 2 + sy * (yMin + yMax) / 2

  def viewX(x: Double, y: Double, z: Double): Double = ox + sx * x
  def viewY(x: Double, y: Double, z: Double): Double = oy - sy * y

  /** Pixels per model unit along x. */
  def scaleX: Double = sx

/**
 * Frame preparation for trail drawing, kept free of JavaFX so it can be tested and timed
 * headless.
 *
 * [[prepare]] projects every trail point once and sorts the segments (point i-1 to point i) into
 * `bands` colour bands with a counting sort. Drawing then needs one path per band: 20 000 points
 * cost `bands` stroke calls, not 20 000. All buffers are allocated once.
 *
 * @param capacity largest trail size
 * @param bands    number of colour bands
 */
final class TrajectoryGeometry(val capacity: Int, val bands: Int = 32):
  require(capacity > 1 && bands > 0)

  /** View coordinates of point i (oldest first). */
  val xs: Array[Double] = new Array(capacity)
  val ys: Array[Double] = new Array(capacity)

  /** Segment end indices grouped by band; band b occupies `order(bandStart(b) until bandStart(b + 1))`. */
  val order: Array[Int] = new Array(capacity)
  val bandStart: Array[Int] = new Array(bands + 1)

  private val bandOf = new Array[Int](capacity)
  private val cursor = new Array[Int](bands)
  private var points = 0

  /** Number of points prepared. */
  def size: Int = points

  /**
   * Projects `trail` and bins its segments.
   *
   * @param byValue colour by the trail's values scaled from [vMin, vMax]; otherwise by age
   *                (oldest in band 0)
   */
  def prepare(
      trail: Trail,
      projector: Projector,
      byValue: Boolean = false,
      vMin: Double = 0.0,
      vMax: Double = 1.0
  ): Unit =
    points = math.min(trail.size, capacity)
    java.util.Arrays.fill(bandStart, 0)
    val span = if vMax > vMin then vMax - vMin else 1.0
    var i = 0
    while i < points do
      val x = trail.x(i)
      val y = trail.y(i)
      val z = trail.z(i)
      xs(i) = projector.viewX(x, y, z)
      ys(i) = projector.viewY(x, y, z)
      if i > 0 then
        val t = if byValue then (trail.value(i) - vMin) / span else 1.0 - trail.age(i)
        val b = if t > 0.0 then math.min(bands - 1, (t * bands).toInt) else 0
        bandOf(i) = b
        bandStart(b + 1) += 1
      i += 1
    // prefix sums turn counts into start offsets
    var b = 0
    while b < bands do
      bandStart(b + 1) += bandStart(b)
      b += 1
    // stable placement: segments keep their order within a band
    System.arraycopy(bandStart, 0, cursor, 0, bands)
    i = 1
    while i < points do
      val band = bandOf(i)
      order(cursor(band)) = i
      cursor(band) += 1
      i += 1

  /** Number of segments in band `b`. */
  def segments(b: Int): Int = bandStart(b + 1) - bandStart(b)
