package physis.fields

/**
 * Shape of a 2D grid: `nx` by `ny` interior cells of size `h`, plus a halo of one cell on every
 * side that boundary conditions write and stencils read.
 *
 * Storage is one `Array[Double]`, row-major, `stride = nx + 2` values per row and `ny + 2` rows.
 * Interior cell `(i, j)` with `0 <= i < nx` and `0 <= j < ny` lives at `index(i, j)`; the halo
 * is reached with `i` or `j` equal to `-1`, `nx` or `ny`.
 *
 * @param nx number of interior columns
 * @param ny number of interior rows
 * @param h  cell size (the same in both directions), in the model's length unit
 */
final case class Grid2D(nx: Int, ny: Int, h: Double = 1.0):
  require(nx >= 1 && ny >= 1, s"grid must have at least one cell, got $nx x $ny")
  require(h > 0.0 && !h.isInfinite, s"cell size must be positive, got $h")

  /** Values per storage row, halo included. */
  val stride: Int = nx + Grid2D.Halo * 2

  /** Rows of storage, halo included. */
  val rows: Int = ny + Grid2D.Halo * 2

  /** Length of the backing array. */
  val storageSize: Int = stride * rows

  /** Number of interior cells. */
  val cells: Int = nx * ny

  /** Position of cell `(i, j)` in the backing array; `-1 <= i <= nx`, `-1 <= j <= ny`. */
  def index(i: Int, j: Int): Int = (j + Grid2D.Halo) * stride + i + Grid2D.Halo

object Grid2D:
  /** Width of the halo in cells. Stencils of radius 1 need exactly this. */
  val Halo: Int = 1
