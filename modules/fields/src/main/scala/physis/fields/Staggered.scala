package physis.fields

/**
 * Operators for a layer of water on the staggered grid of a [[MacGrid]]: the height and the
 * depth at the cell centres, the horizontal velocity `u` on the vertical faces and the vertical
 * velocity `v` on the horizontal faces (an Arakawa C grid).
 *
 * The faces between two sea cells are open; a face next to land, and every face on the border
 * of the domain, is closed and carries no flow. The left and right borders may wrap (a channel);
 * the bottom and top borders never do. On a wrapping axis the last `u` face is a copy of the
 * first, as in [[MacGrid]]. Nothing here allocates.
 */
object Staggered:

  /**
   * Sets `openU` and `openV` to 1 for the faces between two cells that are not land and to 0
   * for all others. `land` is a 0/1 field on the cells.
   */
  def openFaces(land: Field2D, openU: Field2D, openV: Field2D, wrapX: Boolean): Unit =
    val nx = land.nx
    val ny = land.ny
    var j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        val sea =
          if i > 0 && i < nx then land(i - 1, j) == 0.0 && land(i, j) == 0.0
          else wrapX && land(nx - 1, j) == 0.0 && land(0, j) == 0.0
        openU(i, j) = if sea then 1.0 else 0.0
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        val sea = j > 0 && j < ny && land(i, j - 1) == 0.0 && land(i, j) == 0.0
        openV(i, j) = if sea then 1.0 else 0.0
        i += 1
      j += 1

  /**
   * The mean of the two cells on either side of every vertical face: `out(i, j)` is
   * `(c(i - 1, j) + c(i, j)) / 2`. A face on the left or right border has one cell on its side
   * only: it gets the mean with the cell on the other side if `wrapX`, and 0 otherwise.
   */
  def meanToU(c: Field2D, out: Field2D, wrapX: Boolean): Unit =
    val nx = c.nx
    var j = 0
    while j < c.ny do
      var i = 1
      while i < nx do
        out(i, j) = 0.5 * (c(i - 1, j) + c(i, j))
        i += 1
      val edge = if wrapX then 0.5 * (c(nx - 1, j) + c(0, j)) else 0.0
      out(0, j) = edge
      out(nx, j) = edge
      j += 1

  /**
   * The mean of the two cells above and below every horizontal face; the faces on the bottom
   * and top borders get 0.
   */
  def meanToV(c: Field2D, out: Field2D): Unit =
    val ny = c.ny
    var i = 0
    while i < c.nx do
      out(i, 0) = 0.0
      out(i, ny) = 0.0
      i += 1
    var j = 1
    while j < ny do
      i = 0
      while i < c.nx do
        out(i, j) = 0.5 * (c(i, j - 1) + c(i, j))
        i += 1
      j += 1

  /**
   * The mean of the four `v` faces around every `u` face, for the Coriolis term of the `u`
   * equation: `out(i, j)` is the average of `v(i - 1, j)`, `v(i, j)`, `v(i - 1, j + 1)` and
   * `v(i, j + 1)`. Columns beyond a border that does not wrap count as 0.
   */
  def vToU(v: Field2D, out: Field2D, wrapX: Boolean): Unit =
    val nx = v.nx
    var j = 0
    while j < out.ny do
      var i = 0
      while i <= nx do
        val left = if i > 0 then i - 1 else if wrapX then nx - 1 else -1
        val right = if i < nx then i else if wrapX then 0 else -1
        var sum = 0.0
        if left >= 0 then sum += v(left, j) + v(left, j + 1)
        if right >= 0 then sum += v(right, j) + v(right, j + 1)
        out(i, j) = 0.25 * sum
        i += 1
      j += 1

  /**
   * The mean of the four `u` faces around every `v` face, for the Coriolis term of the `v`
   * equation: `out(i, j)` is the average of `u(i, j - 1)`, `u(i + 1, j - 1)`, `u(i, j)` and
   * `u(i + 1, j)`. Rows beyond the bottom and top borders count as 0.
   */
  def uToV(u: Field2D, out: Field2D): Unit =
    val ny = u.ny
    var j = 0
    while j <= ny do
      var i = 0
      while i < out.nx do
        var sum = 0.0
        if j > 0 then sum += u(i, j - 1) + u(i + 1, j - 1)
        if j < ny then sum += u(i, j) + u(i + 1, j)
        out(i, j) = 0.25 * sum
        i += 1
      j += 1

  // --- Wetting and drying -------------------------------------------------------------------
  //
  // A cell is wet when the surface `eta` stands above the bed `bed` (both measured from the same
  // level, up is positive). A dry cell keeps `eta == bed`, so a lake at rest has no slope to push
  // the water anywhere and the depth of a cell is always `eta - bed`.

  /** The depth of the water in a cell: `eta - bed`, never below 0. */
  def waterDepth(eta: Field2D, bed: Field2D, out: Field2D): Unit =
    var j = 0
    while j < eta.ny do
      var i = 0
      while i < eta.nx do
        out(i, j) = math.max(0.0, eta(i, j) - bed(i, j))
        i += 1
      j += 1

  /**
   * The depth of the water over the sill of every vertical face, and whether the face carries
   * flow. Water stands over a sill only as high as the lower of the two surfaces allows: on each
   * side the depth is `eta` minus the higher of the two beds, and the face depth is the mean of
   * the two (so over a flat bed it is the mean depth of the two cells, as in [[meanToU]]). The
   * face is `wet` (1) when either side has more than `eps` of water over the sill, and 0 when
   * both have less; a face on the left or right border is dry unless `wrapX`.
   */
  def sillDepthU(
      eta: Field2D,
      bed: Field2D,
      eps: Double,
      wrapX: Boolean,
      depth: Field2D,
      wet: Field2D
  ): Unit =
    val nx = eta.nx
    var j = 0
    while j < eta.ny do
      var i = 0
      while i <= nx do
        val l = if i > 0 then i - 1 else if wrapX then nx - 1 else -1
        val r = if i < nx then i else if wrapX then 0 else -1
        if l < 0 || r < 0 then
          depth(i, j) = 0.0
          wet(i, j) = 0.0
        else
          val sill = math.max(bed(l, j), bed(r, j))
          val a = math.max(0.0, eta(l, j) - sill)
          val b = math.max(0.0, eta(r, j) - sill)
          depth(i, j) = 0.5 * (a + b)
          wet(i, j) = if math.max(a, b) > eps then 1.0 else 0.0
        i += 1
      j += 1

  /** The same for the horizontal faces; the faces on the bottom and top borders are dry. */
  def sillDepthV(
      eta: Field2D,
      bed: Field2D,
      eps: Double,
      depth: Field2D,
      wet: Field2D
  ): Unit =
    val ny = eta.ny
    var j = 0
    while j <= ny do
      var i = 0
      while i < eta.nx do
        if j == 0 || j == ny then
          depth(i, j) = 0.0
          wet(i, j) = 0.0
        else
          val sill = math.max(bed(i, j - 1), bed(i, j))
          val a = math.max(0.0, eta(i, j - 1) - sill)
          val b = math.max(0.0, eta(i, j) - sill)
          depth(i, j) = 0.5 * (a + b)
          wet(i, j) = if math.max(a, b) > eps then 1.0 else 0.0
        i += 1
      j += 1

  /**
   * Scales the fluxes (depth times velocity, in m²/s) through the faces so that no cell gives
   * away more water in a step of size `dt` than it holds: a cell whose outflow would take more
   * than its depth `water` (m) has all its outflowing fluxes cut by the same factor. Inflow is
   * never touched, so the depth stays at 0 or more and the volume stays exact. `scale` is
   * scratch space of one value per cell. `h` is the size of a cell, in m.
   */
  def limitOutflow(
      fluxU: Field2D,
      fluxV: Field2D,
      water: Field2D,
      dt: Double,
      h: Double,
      wrapX: Boolean,
      scale: Field2D
  ): Unit =
    val nx = water.nx
    val ny = water.ny
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        val out = math.max(0.0, fluxU(i + 1, j)) + math.max(0.0, -fluxU(i, j)) +
          math.max(0.0, fluxV(i, j + 1)) + math.max(0.0, -fluxV(i, j))
        val room = water(i, j) * h
        scale(i, j) = if out * dt > room then room / (out * dt) else 1.0
        i += 1
      j += 1
    j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        val q = fluxU(i, j)
        if q > 0.0 then
          val l = if i > 0 then i - 1 else if wrapX then nx - 1 else -1
          if l >= 0 then fluxU(i, j) = q * scale(l, j)
        else if q < 0.0 then
          val r = if i < nx then i else if wrapX then 0 else -1
          if r >= 0 then fluxU(i, j) = q * scale(r, j)
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
