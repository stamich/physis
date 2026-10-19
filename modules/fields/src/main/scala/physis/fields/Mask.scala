package physis.fields

import physis.core.run.{Disc, Op}

/**
 * A 0/1 field with a running count of its set cells, for walls and other painted regions.
 * The count is kept in step with the cells, so reading it costs nothing.
 */
final class Mask(val grid: Grid2D):
  /** Cell values, 0.0 or 1.0; the halo stays 0. */
  val field: Field2D = new Field2D(grid)

  private var set = 0

  /** Number of cells set to 1. */
  def count: Int = set

  def apply(i: Int, j: Int): Boolean = field(i, j) != 0.0

  /** Sets cell `(i, j)`. */
  def update(i: Int, j: Int, on: Boolean): Unit =
    val k = grid.index(i, j)
    val was = field.data(k) != 0.0
    if was != on then
      field.data(k) = if on then 1.0 else 0.0
      set += (if on then 1 else -1)

  /**
   * Paints the cells inside `disc`: `Assign` with a non-zero value and `Add` with a positive
   * one set cells, `Erase` and `Assign` with zero clear them; `Add` with a non-positive value
   * leaves the mask alone. Returns the number of cells that changed.
   */
  def paint(disc: Disc, wrapX: Boolean, wrapY: Boolean, op: Op, value: Double): Int =
    val on = op match
      case Op.Assign => value != 0.0
      case Op.Add => value > 0.0
      case Op.Erase => false
    val skip = op == Op.Add && !on
    var changed = 0
    if !skip then
      Brush.foreachCell(grid, disc, wrapX, wrapY) { (i, j) =>
        val k = grid.index(i, j)
        val was = field.data(k) != 0.0
        if was != on then
          field.data(k) = if on then 1.0 else 0.0
          set += (if on then 1 else -1)
          changed += 1
      }
    changed

  /** Clears every cell. */
  def clear(): Unit =
    field.fill(0.0)
    set = 0

  /** Recounts from the cells; for use after the field was written directly. */
  def recount(): Unit =
    var n = 0
    var j = 0
    while j < grid.ny do
      var i = 0
      while i < grid.nx do
        if field(i, j) != 0.0 then n += 1
        i += 1
      j += 1
    set = n

/** Smooth bumps stamped onto a field. */
object Pulse:
  /** Cut-off of the Gaussian, in standard deviations; beyond it the stamp adds nothing. */
  val Reach: Double = 6.0

  /**
   * Adds `amplitude * exp(-d² / (2 sigma²))` to every cell whose centre is within
   * [[Reach]] standard deviations of `(x, y)` (cell coordinates, cell `(i, j)` centred at
   * `(i + 0.5, j + 0.5)`). `amplitude` is the peak value. Periodic axes use the minimum-image
   * distance. Returns the number of cells touched.
   */
  def stamp(
      field: Field2D,
      x: Double,
      y: Double,
      sigma: Double,
      amplitude: Double,
      wrapX: Boolean,
      wrapY: Boolean
  ): Int =
    val grid = field.grid
    val data = field.data
    val k = -1.0 / (2.0 * sigma * sigma)
    var touched = 0
    Brush.foreachCellAt(grid, x, y, Reach * sigma, wrapX, wrapY) { (i, j) =>
      var dx = i + 0.5 - x
      var dy = j + 0.5 - y
      if wrapX then dx -= grid.nx * math.rint(dx / grid.nx)
      if wrapY then dy -= grid.ny * math.rint(dy / grid.ny)
      data(grid.index(i, j)) += amplitude * math.exp(k * (dx * dx + dy * dy))
      touched += 1
    }
    touched
