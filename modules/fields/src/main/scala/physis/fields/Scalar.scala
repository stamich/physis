package physis.fields

/**
 * A quantity at the cell centres that the flow carries and that diffuses: dye, a temperature, a
 * concentration. It keeps two copies and swaps them by reference when it is advected, so
 * [[field]] must be read again after [[advect]]. Diffusion is explicit and uses the boundary
 * conditions `bounds` (see [[Boundaries]]); advection is the semi-Lagrangian step of [[Advect]],
 * which reads edge values as they are, so a Dirichlet wall acts through diffusion only. Nothing
 * here allocates after construction.
 *
 * @param grid   the cells
 * @param bounds the condition on each axis, written into the halo before every diffusion step
 */
final class Scalar(val grid: Grid2D, val bounds: Boundaries):
  private val copies = Array(new Field2D(grid), new Field2D(grid))
  private var cur = 0
  private val scratch = new Field2D(grid)

  /** The current values. */
  def field: Field2D = copies(cur)

  /** Carries the values with `flow` for `dt`. */
  def advect(flow: Advect.Flow, dt: Double): Unit =
    Advect.cells(flow, copies(cur), copies(1 - cur), dt)
    cur = 1 - cur

  /** One explicit diffusion step with `s = κ dt / h²` (at most 0.25); does nothing if `s` is 0. */
  def diffuse(s: Double): Unit =
    if s > 0.0 then
      val f = field
      bounds.fill(f)
      Diffuse.step(f, scratch, s)
      f.copyFrom(scratch)
