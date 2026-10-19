package physis.fields

import physis.core.run.{Disc, Op}

/**
 * Rasterizes a [[Disc]] onto a grid and applies brush operations to a [[Field2D]].
 *
 * Cell `(i, j)` has its centre at `(i + 0.5, j + 0.5)` in cell coordinates, so a pointer at
 * pixel position `p` over cells of `s` pixels sits at `p / s`. A cell is in the disc when its
 * centre is within `r` of the disc centre, decided in doubles by comparing squared distances:
 * no trigonometry, so every platform visits the same cells. On a periodic axis the distance
 * wraps (minimum image) and a cell is visited once even when the disc is larger than the grid.
 *
 * Nothing here allocates: [[foreachCell]] is inline, so the body is not a closure.
 */
object Brush:

  /**
   * Calls `f(i, j)` for every interior cell inside `disc`, row by row. Cells beyond a
   * non-periodic edge are skipped.
   */
  inline def foreachCell(grid: Grid2D, disc: Disc, wrapX: Boolean, wrapY: Boolean)(
      inline f: (Int, Int) => Unit
  ): Unit =
    foreachCellAt(grid, disc.x, disc.y, disc.r, wrapX, wrapY)(f)

  /** Like [[foreachCell]] for a disc given by its numbers, which allocates no [[Disc]]. */
  inline def foreachCellAt(
      grid: Grid2D,
      cx: Double,
      cy: Double,
      radius: Double,
      wrapX: Boolean,
      wrapY: Boolean
  )(inline f: (Int, Int) => Unit): Unit =
    val nx = grid.nx
    val ny = grid.ny
    val r2 = radius * radius
    val ilo = math.floor(cx - radius).toInt
    val ihi = math.ceil(cx + radius).toInt
    val jlo = math.floor(cy - radius).toInt
    val jhi = math.ceil(cy + radius).toInt
    val fullX = wrapX && (ihi.toLong - ilo + 1) >= nx
    val fullY = wrapY && (jhi.toLong - jlo + 1) >= ny
    val i0 = if fullX then 0 else if wrapX then ilo else math.max(ilo, 0)
    val i1 = if fullX then nx - 1 else if wrapX then ihi else math.min(ihi, nx - 1)
    val j0 = if fullY then 0 else if wrapY then jlo else math.max(jlo, 0)
    val j1 = if fullY then ny - 1 else if wrapY then jhi else math.min(jhi, ny - 1)
    var j = j0
    while j <= j1 do
      var dy = j + 0.5 - cy
      if wrapY then dy -= ny * math.rint(dy / ny)
      val dy2 = dy * dy
      if dy2 <= r2 then
        val jj = if wrapY then Math.floorMod(j, ny) else j
        var i = i0
        while i <= i1 do
          var dx = i + 0.5 - cx
          if wrapX then dx -= nx * math.rint(dx / nx)
          if dx * dx + dy2 <= r2 then
            val ii = if wrapX then Math.floorMod(i, nx) else i
            f(ii, jj)
          i += 1
      j += 1

  /** Number of cells inside `disc`. */
  def count(grid: Grid2D, disc: Disc, wrapX: Boolean, wrapY: Boolean): Int =
    var n = 0
    foreachCell(grid, disc, wrapX, wrapY)((_, _) => n += 1)
    n

  /**
   * Applies `op` with `value` to every cell of `field` inside `disc`, keeping results within
   * `[lo, hi]`; `Erase` sets zero (clamped like the others). Returns the number of cells
   * touched.
   */
  def apply(
      field: Field2D,
      disc: Disc,
      wrapX: Boolean,
      wrapY: Boolean,
      op: Op,
      value: Double,
      lo: Double = Double.NegativeInfinity,
      hi: Double = Double.PositiveInfinity
  ): Int =
    val data = field.data
    val grid = field.grid
    val mode = op.ordinal
    var touched = 0
    foreachCell(grid, disc, wrapX, wrapY) { (i, j) =>
      val k = grid.index(i, j)
      val next = mode match
        case 0 => value
        case 1 => data(k) + value
        case _ => 0.0
      data(k) = if next < lo then lo else if next > hi then hi else next
      touched += 1
    }
    touched
