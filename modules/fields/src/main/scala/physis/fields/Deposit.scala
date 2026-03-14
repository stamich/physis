package physis.fields

/**
 * Adds an amount to a field at a position between its cells, on a periodic domain: the way an
 * agent leaves something behind. Positions are in cells with cell centres at `(i + 0.5, j + 0.5)`.
 * The amount is shared out so that the total added is the amount itself (up to rounding). No
 * call allocates. Deposits from several agents are added in the order they are made, so a
 * caller that goes through its agents in a fixed order gets the same sums every time.
 */
object Deposit:

  /** Adds `amount` to the cell that contains `(x, y)`. */
  def nearest(f: Field2D, x: Double, y: Double, amount: Double): Unit =
    val i = math.min(f.nx - 1, math.floor(Sampler.wrap(x, f.nx)).toInt)
    val j = math.min(f.ny - 1, math.floor(Sampler.wrap(y, f.ny)).toInt)
    f.data(f.grid.index(i, j)) += amount

  /**
   * Shares `amount` between the four cells whose centres surround `(x, y)`, in proportion to
   * the weights [[Sampler.bilinear]] reads them with (so sampling right after depositing at a
   * cell centre sees the whole amount there).
   */
  def bilinear(f: Field2D, x: Double, y: Double, amount: Double): Unit =
    val gx = Sampler.wrap(x, f.nx) - 0.5
    val gy = Sampler.wrap(y, f.ny) - 0.5
    val fi = math.floor(gx)
    val fj = math.floor(gy)
    val tx = gx - fi
    val ty = gy - fj
    val i0 = Sampler.wrapIndex(fi.toInt, f.nx)
    val j0 = Sampler.wrapIndex(fj.toInt, f.ny)
    val i1 = Sampler.wrapIndex(i0 + 1, f.nx)
    val j1 = Sampler.wrapIndex(j0 + 1, f.ny)
    val g = f.grid
    val d = f.data
    d(g.index(i0, j0)) += amount * (1.0 - tx) * (1.0 - ty)
    d(g.index(i1, j0)) += amount * tx * (1.0 - ty)
    d(g.index(i0, j1)) += amount * (1.0 - tx) * ty
    d(g.index(i1, j1)) += amount * tx * ty
