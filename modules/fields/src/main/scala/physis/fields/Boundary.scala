package physis.fields

/**
 * Boundary condition along one axis of a [[Field2D]], realised by filling the halo.
 *
 * The cell centres of the interior are at half-integer positions, so the boundary itself lies
 * half a cell outside the first interior cell, and each condition has a one-line halo rule:
 *
 *   - [[Periodic]]: the halo holds the opposite interior line (the grid is a torus).
 *   - [[Dirichlet]]: the field has the fixed value `v` on the boundary; the halo holds
 *     `2 v - interior`, so the average of the halo and the adjacent interior cell is `v`.
 *   - [[Neumann]]: zero flux through the boundary; the halo holds the adjacent interior value.
 */
enum Boundary:
  case Periodic
  case Dirichlet(value: Double)
  case Neumann

/**
 * Boundary conditions of a rectangle: one per axis, with `x` for the left and right edges and
 * `y` for the bottom and top (rows 0 and ny - 1).
 */
final case class Boundaries(x: Boundary, y: Boundary):

  /**
   * Fills the halo of `f`. The x edges are filled first, for interior rows; the y edges then
   * fill whole rows including the halo columns, so each corner follows from the two conditions
   * (for example, it equals the diagonal interior cell when both axes are periodic).
   */
  def fill(f: Field2D): Unit =
    fillX(f)
    fillY(f)

  private def fillX(f: Field2D): Unit =
    val nx = f.nx
    val data = f.data
    val g = f.grid
    var j = 0
    while j < g.ny do
      val left = g.index(-1, j)
      val first = g.index(0, j)
      val last = g.index(nx - 1, j)
      val right = g.index(nx, j)
      x match
        case Boundary.Periodic =>
          data(left) = data(last)
          data(right) = data(first)
        case Boundary.Neumann =>
          data(left) = data(first)
          data(right) = data(last)
        case Boundary.Dirichlet(v) =>
          data(left) = 2.0 * v - data(first)
          data(right) = 2.0 * v - data(last)
      j += 1

  private def fillY(f: Field2D): Unit =
    val ny = f.ny
    val data = f.data
    val g = f.grid
    var i = -1
    while i <= g.nx do
      val bottom = g.index(i, -1)
      val first = g.index(i, 0)
      val last = g.index(i, ny - 1)
      val top = g.index(i, ny)
      y match
        case Boundary.Periodic =>
          data(bottom) = data(last)
          data(top) = data(first)
        case Boundary.Neumann =>
          data(bottom) = data(first)
          data(top) = data(last)
        case Boundary.Dirichlet(v) =>
          data(bottom) = 2.0 * v - data(first)
          data(top) = 2.0 * v - data(last)
      i += 1

object Boundaries:
  /** The same condition on both axes. */
  def both(b: Boundary): Boundaries = Boundaries(b, b)

  val periodic: Boundaries = both(Boundary.Periodic)
  val neumann: Boundaries = both(Boundary.Neumann)

  def dirichlet(value: Double): Boundaries = both(Boundary.Dirichlet(value))
