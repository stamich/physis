package physis.fields

/**
 * Operators on the staggered layout of a [[MacGrid]] laid over a [[SphereGrid]]: the same fields
 * as on the plane (`η` at the cell centres, `u` on the vertical faces, `v` on the horizontal
 * ones, `y` and `v` running south), with the widths and areas of the sphere. Longitude always
 * wraps (the last `u` face is a copy of the first) and the faces of the top and bottom rows are
 * walls. Nothing here allocates.
 */
object SphereOps:

  /**
   * `out = ∇·F` in every cell, from the fluxes `fluxU` and `fluxV` (m²/s): the net volume that
   * leaves the cell per second, over its area. `fluxU` goes through a face of length `dy`, `fluxV`
   * through the face above or below the row, of length `lenF`; so the sum of `out · area` over all
   * cells is the flux through the walls, zero when there is none.
   */
  def divergence(fluxU: Field2D, fluxV: Field2D, g: SphereGrid, out: Field2D): Unit =
    val dy = g.dy
    var j = 0
    while j < g.ny do
      val north = g.lenF(j)
      val south = g.lenF(j + 1)
      val inv = 1.0 / g.area(j)
      var i = 0
      while i < g.nx do
        out(i, j) =
          ((fluxU(i + 1, j) - fluxU(i, j)) * dy + fluxV(i, j + 1) * south - fluxV(i, j) * north) *
            inv
        i += 1
      j += 1

  /**
   * The gradient of the cell field `p` along the rows, on the vertical faces: `out(i, j) =
   * (p(i, j) - p(i - 1, j)) / dxC(j)`, the face at column 0 and the last one (a copy of it) taking
   * the cell on the other side of the seam.
   */
  def gradientU(p: Field2D, g: SphereGrid, out: Field2D): Unit =
    val nx = g.nx
    var j = 0
    while j < g.ny do
      val inv = 1.0 / g.dxC(j)
      var i = 1
      while i < nx do
        out(i, j) = (p(i, j) - p(i - 1, j)) * inv
        i += 1
      val seam = (p(0, j) - p(nx - 1, j)) * inv
      out(0, j) = seam
      out(nx, j) = seam
      j += 1

  /**
   * The gradient of the cell field `p` toward the south, on the horizontal faces: `out(i, j) =
   * (p(i, j) - p(i, j - 1)) / dy`; the faces on the top and bottom edges get 0.
   */
  def gradientV(p: Field2D, g: SphereGrid, out: Field2D): Unit =
    val inv = 1.0 / g.dy
    var i = 0
    while i < g.nx do
      out(i, 0) = 0.0
      out(i, g.ny) = 0.0
      i += 1
    var j = 1
    while j < g.ny do
      i = 0
      while i < g.nx do
        out(i, j) = (p(i, j) - p(i, j - 1)) * inv
        i += 1
      j += 1

  /**
   * The relative vorticity of the flow, positive counter-clockwise seen from above with north up,
   * at the cell centres: `ζ = ∂v_N/∂x − ∂u/∂y_N + u tan φ / R`, with `v_N = −v` the northward
   * speed. The velocities are averaged to the centres and differences are centred (one-sided at
   * the top and bottom rows).
   */
  def vorticity(u: Field2D, v: Field2D, g: SphereGrid, out: Field2D): Unit =
    val nx = g.nx
    val ny = g.ny
    var j = 0
    while j < ny do
      val jn = if j > 0 then j - 1 else 0
      val js = if j < ny - 1 then j + 1 else ny - 1
      val dsRows = (js - jn).toDouble
      val invX = 1.0 / (2.0 * g.dxC(j))
      var i = 0
      while i < nx do
        val ie = if i < nx - 1 then i + 1 else 0
        val iw = if i > 0 then i - 1 else nx - 1
        val dvdx = (0.5 * (v(ie, j) + v(ie, j + 1)) - 0.5 * (v(iw, j) + v(iw, j + 1))) * invX
        val uNorth = 0.5 * (u(i, jn) + u(i + 1, jn))
        val uSouth = 0.5 * (u(i, js) + u(i + 1, js))
        val uHere = 0.5 * (u(i, j) + u(i + 1, j))
        // ∂u/∂s with s southward; ∂v_N/∂x = -∂v/∂x and -∂u/∂y_N = +∂u/∂s
        out(i, j) = -dvdx + (uSouth - uNorth) / (dsRows * g.dy) + uHere * g.tanC(j)
        i += 1
      j += 1

  /** `Σ f · area`, in the unit of `f` times m²: the integral of a cell field over the band. */
  def integral(f: Field2D, g: SphereGrid): Double =
    var sum = 0.0
    var j = 0
    while j < g.ny do
      var row = 0.0
      var i = 0
      while i < g.nx do
        row += f(i, j)
        i += 1
      sum += row * g.area(j)
      j += 1
    sum

  /**
   * Scales the fluxes (depth times speed, m²/s) through the faces so that no cell gives away more
   * water in a step of size `dt` than it holds: a cell whose outflow would take more than the
   * volume `water · area` (`water` the depth in m) has all its outflowing fluxes cut by the same
   * factor, inflow is never touched. The sphere's version of [[Staggered.limitOutflow]];
   * `scale` is scratch space of one value per cell.
   */
  def limitOutflow(
      fluxU: Field2D,
      fluxV: Field2D,
      water: Field2D,
      dt: Double,
      g: SphereGrid,
      scale: Field2D
  ): Unit =
    val nx = g.nx
    val ny = g.ny
    val dy = g.dy
    var j = 0
    while j < ny do
      val north = g.lenF(j)
      val south = g.lenF(j + 1)
      var i = 0
      while i < nx do
        val out = (math.max(0.0, fluxU(i + 1, j)) + math.max(0.0, -fluxU(i, j))) * dy +
          math.max(0.0, fluxV(i, j + 1)) * south + math.max(0.0, -fluxV(i, j)) * north
        val room = water(i, j) * g.area(j)
        scale(i, j) = if out * dt > room then room / (out * dt) else 1.0
        i += 1
      j += 1
    j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        val q = fluxU(i, j)
        if q > 0.0 then fluxU(i, j) = q * scale(if i > 0 then i - 1 else nx - 1, j)
        else if q < 0.0 then fluxU(i, j) = q * scale(if i < nx then i else 0, j)
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        val q = fluxV(i, j)
        if q > 0.0 then
          if j > 0 then fluxV(i, j) = q * scale(i, j - 1)
        else if q < 0.0 then
          if j < ny then fluxV(i, j) = q * scale(i, j)
        i += 1
      j += 1
