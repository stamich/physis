package physis.fields

/**
 * Makes a staggered velocity field free of divergence: the pressure projection.
 *
 * A pressure `q` (already multiplied by the time step) is found so that `B q = div(u, v)`, with
 * [[Poisson]] as the solver, and the faces that let flow through are corrected by its gradient,
 * `u += (q(i) - q(i - 1))` on the vertical faces and likewise for `v`. The new velocity has no
 * divergence in any cell, up to the solver's tolerance.
 *
 * Faces that are closed (a wall, a solid cell, a border that neither wraps nor flows out) are
 * never changed by the projection; they keep whatever the caller put there: [[closeFaces]]
 * makes it 0 for a wall, and a moving lid or an inflow is written by the caller after that.
 * With an outflow border the pressure there is 0, so that the flow can leave.
 *
 * The pressure is kept between calls and is the starting guess of the next one, so a step of a
 * smooth flow takes few iterations. Nothing here allocates after construction.
 *
 * @param mac          the staggered grid
 * @param wrapX        the domain is periodic left to right
 * @param wrapY        the domain is periodic bottom to top
 * @param outflowRight the right border is open
 */
final class Projection(
    val mac: MacGrid,
    val wrapX: Boolean,
    val wrapY: Boolean,
    val outflowRight: Boolean
):
  val poisson: Poisson = new Poisson(mac.cells, wrapX, wrapY, outflowRight)

  /** The pressure (times dt) of the latest projection. */
  val pressure: Field2D = new Field2D(mac.cells)

  private val rhs = new Field2D(mac.cells)

  /** Sets which faces are open from the solid cells (non-zero in `solid`) and the borders. */
  def configure(solid: Field2D): Unit = poisson.configure(solid)

  /** Sets every closed face to 0: the wall condition. Call before writing a lid or an inflow. */
  def closeFaces(u: Field2D, v: Field2D): Unit =
    val ou = poisson.openU
    val ov = poisson.openV
    var j = 0
    while j < u.ny do
      var i = 0
      while i < u.nx do
        if ou(i, j) == 0.0 then u(i, j) = 0.0
        i += 1
      j += 1
    j = 0
    while j < v.ny do
      var i = 0
      while i < v.nx do
        if ov(i, j) == 0.0 then v(i, j) = 0.0
        i += 1
      j += 1

  /**
   * Projects `(u, v)` and returns the number of solver iterations. `tolerance` is the
   * root-mean-square residual of the pressure equation, that is the divergence left.
   */
  def project(u: Field2D, v: Field2D, tolerance: Double, maxIterations: Int): Int =
    Mac.divergence(u, v, rhs)
    val iterations = poisson.solve(rhs, pressure, tolerance, maxIterations)
    poisson.fillHalo(pressure)
    val q = pressure
    val ou = poisson.openU
    val ov = poisson.openV
    var j = 0
    while j < u.ny do
      var i = 0
      while i < u.nx do
        u(i, j) += ou(i, j) * (q(i, j) - q(i - 1, j))
        i += 1
      j += 1
    j = 0
    while j < v.ny do
      var i = 0
      while i < v.nx do
        v(i, j) += ov(i, j) * (q(i, j) - q(i, j - 1))
        i += 1
      j += 1
    iterations

  /** The largest divergence of any cell, in the units of the velocity per cell. */
  def maxDivergence(u: Field2D, v: Field2D): Double =
    Mac.divergence(u, v, rhs)
    var m = 0.0
    var j = 0
    while j < rhs.ny do
      var i = 0
      while i < rhs.nx do
        m = math.max(m, math.abs(rhs(i, j)))
        i += 1
      j += 1
    m
