package physis.fields

/**
 * The 5-point Poisson operator on a grid with walls, and a conjugate-gradient solver for it.
 *
 * The operator is `B q = sum over the four sides of open * (q_cell - q_neighbour)`: the
 * negative of the usual Laplacian (for cell size 1), so that it is symmetric and positive.
 * A side is *open* when the face between the two cells lets flow through; a closed side
 * (a wall, the border of a closed box) contributes nothing, which is the Neumann condition. At
 * an *outflow* border the neighbour is a ghost cell held at 0, which is the Dirichlet
 * condition and makes `B` definite. Periodic axes connect the last cell to the first.
 *
 * Faces are numbered as the velocity components of a staggered grid: `u` faces `0..nx` along
 * a row (face `i` lies between cells `i - 1` and `i`) and `v` faces `0..ny` along a column.
 *
 * Nothing here allocates after construction, and sums run in one fixed order (row-major), so a
 * solve gives the same bits on every JVM.
 *
 * @param grid         the cells
 * @param wrapX        the domain is periodic left to right
 * @param wrapY        the domain is periodic bottom to top
 * @param outflowRight the right border is open with pressure 0 (ignored when `wrapX`)
 */
final class Poisson(
    val grid: Grid2D,
    val wrapX: Boolean,
    val wrapY: Boolean,
    val outflowRight: Boolean
):
  private val nx = grid.nx
  private val ny = grid.ny

  /** Grid of the `u` faces: `nx + 1` columns. */
  val uGrid: Grid2D = Grid2D(nx + 1, ny, grid.h)

  /** Grid of the `v` faces: `ny + 1` rows. */
  val vGrid: Grid2D = Grid2D(nx, ny + 1, grid.h)

  /** 1.0 where the `u` face lets flow through, else 0.0. */
  val openU: Field2D = new Field2D(uGrid)

  /** 1.0 where the `v` face lets flow through, else 0.0. */
  val openV: Field2D = new Field2D(vGrid)

  private val r = new Field2D(grid)
  private val d = new Field2D(grid)
  private val ap = new Field2D(grid)
  private val unknown = new Field2D(grid)
  private var active = 0
  private var finalResidual = 0.0

  /** Cells that have at least one open side: the unknowns of the system. */
  def activeCells: Int = active

  /** Root-mean-square residual `|b - B q|` over the unknowns at the end of the latest solve. */
  def residual: Double = finalResidual

  /**
   * Sets which faces are open from the cells held solid (non-zero in `solid`) and from the
   * borders. Call again after the solid cells change.
   */
  def configure(solid: Field2D): Unit =
    if solid.grid != grid then
      throw new IllegalArgumentException(s"shape mismatch: ${solid.grid} vs $grid")
    def free(i: Int, j: Int): Boolean = solid(i, j) == 0.0
    var j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        val open =
          if i == 0 || i == nx then
            if wrapX then free(nx - 1, j) && free(0, j)
            else if i == nx && outflowRight then free(nx - 1, j)
            else false
          else free(i - 1, j) && free(i, j)
        openU(i, j) = if open then 1.0 else 0.0
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        val open =
          if j == 0 || j == ny then if wrapY then free(i, ny - 1) && free(i, 0) else false
          else free(i, j - 1) && free(i, j)
        openV(i, j) = if open then 1.0 else 0.0
        i += 1
      j += 1
    var count = 0
    j = 0
    while j < ny do
      var i = 0
      while i < nx do
        val isUnknown = openU(i, j) != 0.0 || openU(i + 1, j) != 0.0 || openV(i, j) != 0.0 ||
          openV(i, j + 1) != 0.0
        unknown(i, j) = if isUnknown then 1.0 else 0.0
        if isUnknown then count += 1
        i += 1
      j += 1
    active = count

  /** Writes the halo of `q` that the operator reads: wrapped, or 0 beyond a border. */
  private def fillHalo(q: Field2D): Unit =
    var j = 0
    while j < ny do
      q(-1, j) = if wrapX then q(nx - 1, j) else 0.0
      q(nx, j) = if wrapX then q(0, j) else 0.0
      j += 1
    var i = 0
    while i < nx do
      q(i, -1) = if wrapY then q(i, ny - 1) else 0.0
      q(i, ny) = if wrapY then q(i, 0) else 0.0
      i += 1

  /** `out = B q`. The halo of `q` is overwritten. */
  def apply(q: Field2D, out: Field2D): Unit =
    fillHalo(q)
    val qd = q.data
    val od = out.data
    val ou = openU.data
    val ov = openV.data
    val stride = grid.stride
    var j = 0
    while j < ny do
      var k = grid.index(0, j)
      var ku = uGrid.index(0, j)
      var kv = vGrid.index(0, j)
      val vStride = vGrid.stride
      var i = 0
      while i < nx do
        val c = qd(k)
        od(k) =
          ((ou(ku) * (c - qd(k - 1)) + ou(ku + 1) * (c - qd(k + 1))) +
            ov(kv) * (c - qd(k - stride))) + ov(kv + vStride) * (c - qd(k + stride))
        k += 1
        ku += 1
        kv += 1
        i += 1
      j += 1

  private def dot(a: Field2D, b: Field2D): Double =
    val ad = a.data
    val bd = b.data
    var sum = 0.0
    var j = 0
    while j < ny do
      var k = grid.index(0, j)
      val end = k + nx
      while k < end do
        sum += ad(k) * bd(k)
        k += 1
      j += 1
    sum

  /**
   * Solves `B q = b` by conjugate gradients, starting from the `q` it is given (a warm start
   * from the previous solution makes a time step cheap). Stops when the root-mean-square
   * residual over the unknowns is at most `tolerance`, or after `maxIterations`. Returns the
   * number of iterations; [[residual]] has the final residual.
   *
   * Without an outflow border `B` is singular (constants give 0): the mean of `b` over the
   * unknowns is removed, which is the only `b` such a system can have an answer for, and the
   * solution is fixed only up to a constant.
   */
  def solve(b: Field2D, q: Field2D, tolerance: Double, maxIterations: Int): Int =
    if b.grid != grid || q.grid != grid then
      throw new IllegalArgumentException(s"shape mismatch: ${b.grid} / ${q.grid} vs $grid")
    apply(q, ap)
    val rd = r.data
    val bd = b.data
    val ad = ap.data
    val singular = !outflowRight || wrapX
    // r = b - B q on the unknowns (0 elsewhere), less its mean when B is singular
    val ud = unknown.data
    var sum = 0.0
    var j = 0
    while j < ny do
      var k = grid.index(0, j)
      val end = k + nx
      while k < end do
        rd(k) = if ud(k) != 0.0 then bd(k) - ad(k) else 0.0
        sum += rd(k)
        k += 1
      j += 1
    val shift = if singular && active > 0 then sum / active else 0.0
    if shift != 0.0 then
      j = 0
      while j < ny do
        var k = grid.index(0, j)
        val end = k + nx
        while k < end do
          if ud(k) != 0.0 then rd(k) -= shift
          k += 1
        j += 1
    d.copyFrom(r)
    var rr = dot(r, r)
    val limit = tolerance * tolerance * math.max(1, active)
    var iterations = 0
    while iterations < maxIterations && rr > limit do
      apply(d, ap)
      val dAd = dot(d, ap)
      if !(dAd > 0.0) then iterations = maxIterations
      else
        val alpha = rr / dAd
        val qd = q.data
        val dd = d.data
        var jj = 0
        while jj < ny do
          var k = grid.index(0, jj)
          val end = k + nx
          while k < end do
            qd(k) += alpha * dd(k)
            rd(k) -= alpha * ad(k)
            k += 1
          jj += 1
        val rrNew = dot(r, r)
        val beta = rrNew / rr
        rr = rrNew
        jj = 0
        while jj < ny do
          var k = grid.index(0, jj)
          val end = k + nx
          while k < end do
            dd(k) = rd(k) + beta * dd(k)
            k += 1
          jj += 1
        iterations += 1
    finalResidual = math.sqrt(rr / math.max(1, active))
    iterations
