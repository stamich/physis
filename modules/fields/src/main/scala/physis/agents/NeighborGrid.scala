package physis.agents

/**
 * Fixed-radius neighbour search on a periodic rectangle, by binning particles into a uniform
 * grid of cells at least one radius wide.
 *
 * [[rebuild]] sorts particle indices into cells with a stable counting sort, so each cell lists
 * its particles in ascending index order. [[foreachNear]] then visits the 3 x 3 cells around a
 * particle in a fixed order (rows bottom to top, columns left to right, wrapping around), so
 * every sum over neighbours adds the same terms in the same order on every run and platform.
 *
 * With fewer than 3 cells along either axis the 3 x 3 block would visit a cell twice, so the
 * grid degenerates to a single cell holding every particle: correct, and quadratic, which is
 * fine because that only happens when the radius is a third of the world or more.
 *
 * Distances are minimum-image distances, computed exactly as in `physis.verify.Neighbours` (the
 * reference the tests compare with): two particles are neighbours when
 * `dx * dx + dy * dy <= radius * radius`, the boundary included. The cell size exceeds the radius
 * by a relative margin of 1e-9, so a coordinate that rounds into the wrong cell can never hide a
 * neighbour.
 *
 * Positions must lie in `[0, width]` and `[0, height]`. Not thread-safe: one owner at a time.
 *
 * @param capacity maximum number of particles
 * @param width    size of the world along x
 * @param height   size of the world along y
 * @param radius   neighbour distance
 */
final class NeighborGrid(
    val capacity: Int,
    val width: Double,
    val height: Double,
    val radius: Double
):
  require(capacity >= 1, s"capacity must be positive, got $capacity")
  require(width > 0.0 && height > 0.0, s"world must have a positive size, got $width x $height")
  require(radius > 0.0 && !radius.isInfinite, s"radius must be positive, got $radius")

  private val cellSize = radius * (1.0 + NeighborGrid.Margin)
  private val wantedX = math.max(1, math.floor(width / cellSize).toInt)
  private val wantedY = math.max(1, math.floor(height / cellSize).toInt)

  /** True when there are fewer than 3 cells along an axis, so every query scans all particles. */
  val usesFallback: Boolean = wantedX < 3 || wantedY < 3

  /** Number of cells along x (1 in the fallback). */
  val cellsX: Int = if usesFallback then 1 else wantedX

  /** Number of cells along y (1 in the fallback). */
  val cellsY: Int = if usesFallback then 1 else wantedY

  // Internal state, public only because foreachNear is inline and expands at the call site.
  val cellWidth: Double = width / cellsX
  val cellHeight: Double = height / cellsY
  val span: Int = if usesFallback then 0 else 1
  val cellOf: Array[Int] = new Array[Int](capacity)
  val cellStart: Array[Int] = new Array[Int](cellsX * cellsY + 1)
  val items: Array[Int] = new Array[Int](capacity)
  private val cursor: Array[Int] = new Array[Int](cellsX * cellsY)
  var xs: Array[Double] = new Array[Double](0)
  var ys: Array[Double] = new Array[Double](0)
  var count: Int = 0

  /** Bins the first `n` particles at positions `(x(i), y(i))`. Allocation-free. */
  def rebuild(x: Array[Double], y: Array[Double], n: Int): Unit =
    if n < 0 || n > capacity then
      throw new IllegalArgumentException(s"particle count $n outside 0..$capacity")
    xs = x
    ys = y
    count = n
    java.util.Arrays.fill(cellStart, 0)
    var i = 0
    while i < n do
      val c = cellIndex(x(i), y(i))
      cellOf(i) = c
      cellStart(c + 1) += 1
      i += 1
    var c = 0
    while c < cursor.length do
      cellStart(c + 1) += cellStart(c)
      cursor(c) = cellStart(c)
      c += 1
    i = 0
    while i < n do
      val cell = cellOf(i)
      items(cursor(cell)) = i
      cursor(cell) += 1
      i += 1

  private def cellIndex(x: Double, y: Double): Int =
    val cx = math.max(0, math.min(cellsX - 1, (x / cellWidth).toInt))
    val cy = math.max(0, math.min(cellsY - 1, (y / cellHeight).toInt))
    cy * cellsX + cx

  /**
   * Calls `f(j, dx, dy, d2)` for every particle `j != i` within the radius of particle `i`, with
   * the minimum-image displacement `(dx, dy)` from `i` to `j` and `d2 = dx * dx + dy * dy`.
   * Each neighbour is visited once. Inline, so the callback costs no allocation; the grid must
   * have been rebuilt for the current positions.
   */
  inline def foreachNear(i: Int)(inline f: (Int, Double, Double, Double) => Unit): Unit =
    val xi = xs(i)
    val yi = ys(i)
    val r2 = radius * radius
    val home = cellOf(i)
    val cx = home % cellsX
    val cy = home / cellsX
    var oy = -span
    while oy <= span do
      val row = ((cy + oy + cellsY) % cellsY) * cellsX
      var ox = -span
      while ox <= span do
        val cell = row + (cx + ox + cellsX) % cellsX
        var p = cellStart(cell)
        val end = cellStart(cell + 1)
        while p < end do
          val j = items(p)
          if j != i then
            val dx = NeighborGrid.displacement(xi, xs(j), width)
            val dy = NeighborGrid.displacement(yi, ys(j), height)
            val d2 = dx * dx + dy * dy
            if d2 <= r2 then f(j, dx, dy, d2)
          p += 1
        ox += 1
      oy += 1

object NeighborGrid:
  /** Relative extra width of a cell over the radius. */
  val Margin: Double = 1e-9

  /** Wrapped coordinate difference `to - from` on a period `size`, as in the reference search. */
  inline def displacement(from: Double, to: Double, size: Double): Double =
    val d = to - from
    if d > 0.5 * size then d - size
    else if d < -0.5 * size then d + size
    else d
