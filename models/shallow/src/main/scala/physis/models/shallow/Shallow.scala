package physis.models.shallow

import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.core.run.{Disc, Intervention, Op}
import physis.fields.{Advect, Brush, Field2D, Mac, MacGrid, Mask, Pulse, Staggered}

/**
 * Parameters of the shallow-water model. Lengths are in metres, times in seconds.
 *
 * @param gravity the acceleration of gravity `g`, in m/s²
 * @param depth   the depth of the water at rest, in m
 * @param dx      the size of a cell, in m (needs a restart)
 * @param f0      the Coriolis parameter `f = 2 Ω sin φ` at the middle row of the plane, in 1/s;
 *                positive in the northern hemisphere, with north at the top of the picture
 * @param beta    how fast `f` grows toward the north (up the picture), in 1/(m s): 0 is an f-plane
 * @param friction the rate at which the bottom slows the flow, in 1/s
 * @param nonlinear 0 for linear waves (small against the depth), 1 for the full equations:
 *                the depth of the moving water and the advection of the flow by itself
 * @param edges   the borders (needs a restart): 0 a channel, wrapping left and right, with walls
 *                above and below; 1 a closed basin; 2 an open sea whose borders absorb waves
 * @param sponge  width in cells of the absorbing strip along the borders of an open sea
 * @param seabed  the shape of the sea floor (needs a restart): 0 flat, 1 a shelf that rises
 *                toward the right, 2 a seamount in the middle, 3 a plane beach that comes out of
 *                the water, 4 the beach with a bay (3 and 4 need flooding)
 * @param start   how the run begins (needs a restart): 0 at rest, 1 a front, 2 a Rossby wave,
 *                3 a solitary wave going right, 4 an N-wave (a dip ahead of a hump) going right
 * @param amp     the size of the start, in m: the height of the front
 * @param nx      grid columns (needs a restart)
 * @param ny      grid rows (needs a restart)
 * @param flooding 0 for a coast that is a wall, 1 for a coast that floods and dries: water can
 *                run up over land and leave it again (needs a restart, and `nonlinear = 1`)
 * @param dryDepth the depth of water, in m, under which a face carries no flow: water thinner
 *                than this over a sill stays where it is
 * @param slope   how steep the beach of seabed 3 and 4 is, in m of rise per m (needs a restart)
 */
final case class ShallowParams(
    gravity: Double,
    depth: Double,
    dx: Double,
    f0: Double,
    beta: Double,
    friction: Double,
    nonlinear: Int,
    edges: Int,
    sponge: Int,
    seabed: Int,
    start: Int,
    amp: Double,
    nx: Int,
    ny: Int,
    flooding: Int = 0,
    dryDepth: Double = 0.01,
    slope: Double = 0.05
)

object ShallowParams:
  given schema: ParamSchema[ShallowParams] = ParamSchema.derived[ShallowParams](
    ParamSpec(
      "gravity",
      default = 9.81,
      min = 0.1,
      max = 30.0,
      unit = "m/s²",
      doc = "Acceleration of gravity"
    ),
    ParamSpec(
      "depth",
      default = 4000.0,
      min = 1.0,
      max = 7000.0,
      unit = "m",
      doc = "Depth of the water at rest: waves travel at sqrt(g depth)"
    ),
    ParamSpec(
      "dx",
      default = 20_000.0,
      min = 0.01,
      max = 100_000.0,
      unit = "m",
      doc = "Size of a cell",
      restart = true
    ),
    ParamSpec(
      "f0",
      default = 1e-4,
      min = -5e-4,
      max = 5e-4,
      unit = "1/s",
      doc = "Coriolis parameter f = 2 Ω sin(latitude); 0 switches rotation off"
    ),
    ParamSpec(
      "beta",
      default = 0.0,
      min = 0.0,
      max = 1e-9,
      unit = "1/(m s)",
      doc =
        "Growth of the Coriolis parameter toward the north (the beta effect); 0 keeps f constant"
    ),
    ParamSpec(
      "friction",
      default = 0.0,
      min = 0.0,
      max = 1e-3,
      unit = "1/s",
      doc = "Bottom friction: the rate at which the flow is slowed"
    ),
    ParamSpec(
      "nonlinear",
      default = 0,
      min = 0,
      max = 1,
      doc = "0 linear waves, 1 with the depth of the moving water and the advection of the flow"
    ),
    ParamSpec(
      "edges",
      default = 1,
      min = 0,
      max = 2,
      doc =
        "Borders: 0 channel (wraps left and right), 1 closed basin, 2 open sea that absorbs waves",
      restart = true
    ),
    ParamSpec(
      "sponge",
      default = 10,
      min = 0,
      max = 64,
      unit = "cells",
      doc = "Width of the absorbing strip along the borders of an open sea",
      restart = true
    ),
    ParamSpec(
      "seabed",
      default = 0,
      min = 0,
      max = 4,
      doc =
        "Sea floor: 0 flat, 1 a shelf, 2 a seamount, 3 a plane beach, 4 a beach with a bay (3 and 4 need flooding)",
      restart = true
    ),
    ParamSpec(
      "start",
      default = 0,
      min = 0,
      max = 4,
      doc =
        "How the run begins: 0 at rest, 1 a step in the height, 2 a Rossby wave, 3 a solitary wave, 4 an N-wave",
      restart = true
    ),
    ParamSpec(
      "amp",
      default = 0.2,
      min = 0.0,
      max = 20.0,
      unit = "m",
      doc = "Size of the start: the height of the step or of the wave",
      restart = true
    ),
    ParamSpec("nx", default = 128, min = 16, max = 512, doc = "Grid columns", restart = true),
    ParamSpec("ny", default = 96, min = 16, max = 512, doc = "Grid rows", restart = true),
    ParamSpec(
      "flooding",
      default = 0,
      min = 0,
      max = 1,
      doc = "0 a coast is a wall, 1 water floods and dries the land (needs nonlinear = 1)",
      restart = true
    ),
    ParamSpec(
      "dryDepth",
      default = 0.01,
      min = 1e-6,
      max = 10.0,
      unit = "m",
      doc = "Water thinner than this over a sill does not flow (flooding only)"
    ),
    ParamSpec(
      "slope",
      default = 0.05,
      min = 0.001,
      max = 1.0,
      unit = "m/m",
      doc = "Slope of the beach of the sea floors 3 and 4: rise per distance",
      restart = true
    )
  )

/**
 * State of a layer of water on a rotating plane. The height of the surface `eta` above rest is
 * at the cell centres, the horizontal velocity `u` on the vertical faces and the vertical
 * velocity `v` on the horizontal faces. Cells painted as land hold no water and the faces next
 * to them, like the borders of the domain, carry no flow.
 *
 * The equations are
 *
 * {{{
 *   ∂η/∂t = -∇·(h u)                       (h = the depth of the water)
 *   ∂u/∂t = -g ∂η/∂x - f v
 *   ∂v/∂t = -g ∂η/∂y + f u
 * }}}
 *
 * `x` runs east, along the rows, and `y` runs south, down the picture: row 0 is the northern
 * edge, a positive `v` flows south, and the Coriolis parameter `f` (positive in the northern
 * hemisphere) grows toward the north. So water that flows east turns toward the south, to its
 * right, and a hump swirls clockwise, as on a map.
 *
 * The first is in flux form, so the volume of water changes only through the border: it is
 * conserved to rounding. A step is forward–backward: the height first, from the old flow, then
 * the velocity from the new height. The Coriolis terms use the newest `u` for `v`, which keeps
 * the rotation from gaining energy.
 */
final class ShallowState(val mac: MacGrid, val wrapX: Boolean):
  private val nx = mac.nx
  private val ny = mac.ny

  /** The height of the surface above rest, in m. */
  val eta: Field2D = new Field2D(mac.cells)

  private val us = Array(new Field2D(mac.uFaces), new Field2D(mac.uFaces))
  private val vs = Array(new Field2D(mac.vFaces), new Field2D(mac.vFaces))
  private val flows = Array(
    new Advect.Flow(us(0), vs(0), nx, ny, wrapX, false),
    new Advect.Flow(us(1), vs(1), nx, ny, wrapX, false)
  )
  private var cur = 0

  /** The depth of the water at rest as a fraction of the `depth` parameter: 1 over a flat floor. */
  val shape: Field2D = new Field2D(mac.cells)
  shape.fill(1.0)

  // The parameters the measurements below need; set by `configure` and by every step.
  private var nonlinear = false
  private var depthNow = 0.0
  private var gravityNow = 0.0

  /** The horizontal velocity, on the vertical faces, in m/s. */
  def u: Field2D = us(cur)

  /** The vertical velocity, on the horizontal faces, in m/s. */
  def v: Field2D = vs(cur)

  /** Cells that are land. */
  val land: Mask = new Mask(mac.cells)

  /** The faces that water can flow through, 1 or 0. */
  val openU: Field2D = new Field2D(mac.uFaces)
  val openV: Field2D = new Field2D(mac.vFaces)

  private val total = new Field2D(mac.cells)
  private val thickU = new Field2D(mac.uFaces)
  private val thickV = new Field2D(mac.vFaces)
  private val fluxU = new Field2D(mac.uFaces)
  private val fluxV = new Field2D(mac.vFaces)
  private val vAtU = new Field2D(mac.uFaces)
  private val uAtV = new Field2D(mac.vFaces)
  private val divergence = new Field2D(mac.cells)
  private val scratchC = new Field2D(mac.cells)

  // Wetting and drying (flooding = 1). A dry cell keeps its surface at its bed, so a lake at rest
  // has nothing to push the water; see [[Staggered]].
  private var flooding = false
  private var dryNow = 0.0
  private val bed = new Field2D(mac.cells)
  private val waterC = new Field2D(mac.cells)
  private val sillU = new Field2D(mac.uFaces)
  private val sillV = new Field2D(mac.vFaces)
  private val wetU = new Field2D(mac.uFaces)
  private val wetV = new Field2D(mac.vFaces)
  private val scaleC = new Field2D(mac.cells)

  // What an absorbing strip keeps of a value in one step, at the cells and at the two kinds of
  // faces; null while there is no strip.
  private var keepC: Field2D = null
  private var keepU: Field2D = null
  private var keepV: Field2D = null

  Staggered.openFaces(land.field, openU, openV, wrapX)

  /** Call after the land mask changed. */
  def landChanged(): Unit =
    Staggered.openFaces(land.field, openU, openV, wrapX)
    closeFaces()
    clearLand()

  /** Stops the flow through the closed faces. */
  def closeFaces(): Unit =
    val u = this.u
    val v = this.v
    var j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        if openU(i, j) == 0.0 then u(i, j) = 0.0
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        if openV(i, j) == 0.0 then v(i, j) = 0.0
        i += 1
      j += 1

  /**
   * Land holds no water. With flooding the surface of every cell is also put at or above its
   * bed (water cannot be dug out of the floor), and the surface of land at rest.
   */
  def clearLand(): Unit =
    if flooding then settle()
    else if land.count > 0 then
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          if land(i, j) then eta(i, j) = 0.0
          i += 1
        j += 1

  /**
   * The depth of the water in every cell: the depth at rest, plus the height if the equations
   * are nonlinear (but never below 0: the model has no flooding, a coast is a wall).
   */
  private def refreshTotal(): Unit =
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        total(i, j) = thickness(i, j)
        i += 1
      j += 1

  /** The depth of the water in cell `(i, j)`, see [[refreshTotal]]. */
  private def thickness(i: Int, j: Int): Double =
    if flooding then math.max(0.0, eta(i, j) - bed(i, j))
    else
      val rest = depthNow * shape(i, j)
      if nonlinear then math.max(0.0, rest + eta(i, j)) else rest

  /** Takes the parameters that the measurements and the next step need. */
  def configure(p: ShallowParams): Unit =
    nonlinear = p.nonlinear != 0
    depthNow = p.depth
    gravityNow = p.gravity
    flooding = p.flooding != 0
    dryNow = p.dryDepth
    if flooding then refreshBed()

  /** The bed, in m above the level of the sea at rest: minus the depth at rest. */
  private def refreshBed(): Unit =
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        bed(i, j) = -depthNow * shape(i, j)
        i += 1
      j += 1

  /**
   * With flooding: puts the surface of every cell at or above its bed and the surface of land
   * at the level of rest, `max(0, bed)`. Call after the sea floor, the land or the surface was
   * set from outside. Without flooding it does nothing.
   */
  def settle(): Unit =
    if flooding then
      refreshBed()
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          eta(i, j) =
            if land(i, j) then math.max(0.0, bed(i, j)) else math.max(eta(i, j), bed(i, j))
          i += 1
        j += 1

  /**
   * Makes the outer `width` cells absorb waves: every step they keep a smaller part of the
   * height and of the velocity, the less the deeper inside the strip (a quadratic ramp, down to
   * [[Shallow.SpongeStrength]] per step at the border). A width of 0 removes the strip.
   */
  def sponge(width: Int): Unit =
    if width <= 0 then
      keepC = null
      keepU = null
      keepV = null
    else
      keepC = new Field2D(mac.cells)
      keepU = new Field2D(mac.uFaces)
      keepV = new Field2D(mac.vFaces)
      val w = width.toDouble
      def keep(dx: Double, dy: Double): Double =
        val d = math.min(dx, dy)
        if d >= w then 1.0 else 1.0 - Shallow.SpongeStrength * (w - d) * (w - d) / (w * w)
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          keepC(i, j) = keep(math.min(i + 0.5, nx - i - 0.5), math.min(j + 0.5, ny - j - 0.5))
          i += 1
        i = 0
        while i <= nx do
          keepU(i, j) =
            keep(math.min(i.toDouble, (nx - i).toDouble), math.min(j + 0.5, ny - j - 0.5))
          i += 1
        j += 1
      j = 0
      while j <= ny do
        var i = 0
        while i < nx do
          keepV(i, j) =
            keep(math.min(i + 0.5, nx - i - 0.5), math.min(j.toDouble, (ny - j).toDouble))
          i += 1
        j += 1

  private def absorb(): Unit =
    if keepC != null then
      val u = this.u
      val v = this.v
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          if flooding then
            val rest = math.max(0.0, bed(i, j))
            eta(i, j) = rest + (eta(i, j) - rest) * keepC(i, j)
          else eta(i, j) *= keepC(i, j)
          i += 1
        i = 0
        while i <= nx do
          u(i, j) *= keepU(i, j)
          i += 1
        j += 1
      j = 0
      while j <= ny do
        var i = 0
        while i < nx do
          v(i, j) *= keepV(i, j)
          i += 1
        j += 1

  /** The height equation: `η -= dt ∇·(h u)`, with the flux through the open faces only. */
  private def continuity(dt: Double): Unit =
    val u = this.u
    val v = this.v
    refreshTotal()
    Staggered.meanToU(total, thickU, wrapX)
    Staggered.meanToV(total, thickV)
    var j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        fluxU(i, j) = openU(i, j) * thickU(i, j) * u(i, j)
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        fluxV(i, j) = openV(i, j) * thickV(i, j) * v(i, j)
        i += 1
      j += 1
    Mac.divergence(fluxU, fluxV, divergence)
    j = 0
    while j < ny do
      var i = 0
      while i < nx do
        eta(i, j) -= dt * divergence(i, j)
        i += 1
      j += 1

  /**
   * The height equation with wetting and drying: the same `η -= dt ∇·(h u)`, with the depth over
   * the sill of each face (see [[Staggered.sillDepthU]]), no flux through a face that is dry, and
   * the outflow of every cell cut to the water it holds. The volume stays exact, no cell gets
   * less than 0 of water, and the surface of a dry cell stays at its bed.
   */
  private def floodContinuity(dt: Double): Unit =
    val u = this.u
    val v = this.v
    Staggered.waterDepth(eta, bed, waterC)
    Staggered.sillDepthU(eta, bed, dryNow, wrapX, sillU, wetU)
    Staggered.sillDepthV(eta, bed, dryNow, sillV, wetV)
    var j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        fluxU(i, j) = openU(i, j) * wetU(i, j) * sillU(i, j) * u(i, j)
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        fluxV(i, j) = openV(i, j) * wetV(i, j) * sillV(i, j) * v(i, j)
        i += 1
      j += 1
    Staggered.limitOutflow(fluxU, fluxV, waterC, dt, mac.h, wrapX, scaleC)
    Mac.divergence(fluxU, fluxV, divergence)
    j = 0
    while j < ny do
      var i = 0
      while i < nx do
        eta(i, j) = math.max(bed(i, j), eta(i, j) - dt * divergence(i, j))
        i += 1
      j += 1

  /** Stops the flow through the faces that carry no water, from the surface as it is now. */
  private def stopDryFaces(): Unit =
    val u = this.u
    val v = this.v
    Staggered.sillDepthU(eta, bed, dryNow, wrapX, sillU, wetU)
    Staggered.sillDepthV(eta, bed, dryNow, sillV, wetV)
    var j = 0
    while j < ny do
      var i = 0
      while i <= nx do
        if wetU(i, j) == 0.0 then u(i, j) = 0.0
        i += 1
      j += 1
    j = 0
    while j <= ny do
      var i = 0
      while i < nx do
        if wetV(i, j) == 0.0 then v(i, j) = 0.0
        i += 1
      j += 1

  /** The flow carries itself along: `u` and `v` advected by the flow, semi-Lagrangian. */
  private def advectVelocity(dt: Double): Unit =
    val from = cur
    val to = 1 - cur
    Advect.facesU(flows(from), us(from), us(to), dt)
    Advect.facesV(flows(from), vs(from), vs(to), dt)
    cur = to
    closeFaces()

  /** The velocity equations: the slope of the surface and the rotation, from the new height. */
  private def momentum(p: ShallowParams, dt: Double): Unit =
    val u = this.u
    val v = this.v
    val g = p.gravity
    val inv = 1.0 / p.dx
    val r = p.friction
    Staggered.vToU(v, vAtU, wrapX)
    var j = 0
    while j < ny do
      val f = Shallow.coriolis(p, j + 0.5)
      var i = 0
      while i < nx do
        if openU(i, j) != 0.0 then
          val west = if i > 0 then eta(i - 1, j) else eta(nx - 1, j)
          u(i, j) += dt * (-g * (eta(i, j) - west) * inv - f * vAtU(i, j) - r * u(i, j))
        i += 1
      if wrapX then u(nx, j) = u(0, j)
      j += 1
    Staggered.uToV(u, uAtV)
    j = 1
    while j < ny do
      val f = Shallow.coriolis(p, j.toDouble)
      var i = 0
      while i < nx do
        if openV(i, j) != 0.0 then
          v(i, j) += dt * (-g * (eta(i, j) - eta(i, j - 1)) * inv + f * uAtV(i, j) - r * v(i, j))
        i += 1
      j += 1
    if flooding then stopDryFaces()

  /** One step of size `dt`. */
  def step(p: ShallowParams, dt: Double): Unit =
    configure(p)
    if flooding then floodContinuity(dt) else continuity(dt)
    if nonlinear then advectVelocity(dt)
    momentum(p, dt)
    absorb()

  /** Paints land or height, see [[Shallow.intervene]]. */
  def applyBrush(target: String, disc: Disc, op: Op, value: Double): Unit =
    target match
      case "land" =>
        val _ = land.paint(disc, wrapX, false, op, value)
        landChanged()
      case _ =>
        val _ = Brush(eta, disc, wrapX, false, op, value)
        clearLand()

  /** Adds a Gaussian of height (`amplitude` negative lowers) centred at `(x, y)` in cells. */
  def applyPulse(x: Double, y: Double, sigma: Double, amplitude: Double): Unit =
    val _ = Pulse.stamp(eta, x, y, sigma, amplitude, wrapX, false)
    clearLand()

  /** Writes the height of the surface, row by row, from `offset`. */
  def heightInto(out: Array[Double], offset: Int): Unit =
    eta.copyInteriorInto(out, offset)

  /** Writes the depth of the water at rest, row by row, from `offset`. */
  def depthInto(out: Array[Double], offset: Int): Unit =
    var k = offset
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        out(k) = depthNow * shape(i, j)
        k += 1
        i += 1
      j += 1

  /** Writes the speed at the cell centres, row by row, from `offset`. */
  def speedInto(out: Array[Double], offset: Int): Unit =
    val u = this.u
    val v = this.v
    var k = offset
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        val a = 0.5 * (u(i, j) + u(i + 1, j))
        val b = 0.5 * (v(i, j) + v(i, j + 1))
        out(k) = math.sqrt(a * a + b * b)
        k += 1
        i += 1
      j += 1

  /**
   * Writes the relative vorticity `∂v/∂x - ∂u/∂y` of the flow seen from above with north up
   * (positive is counter-clockwise), at the cell centres, row by row, from `offset`. The model's
   * `y` and `v` run south, which turns the sign of the plain curl.
   */
  def vorticityInto(out: Array[Double], offset: Int): Unit =
    Mac.vorticity(u, v, scratchC, wrapX, false)
    var k = offset
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        out(k) = -scratchC(i, j)
        k += 1
        i += 1
      j += 1

  /** The volume of water above rest, `∫ η`, in m³. */
  def volume: Double =
    if flooding then
      var sum = 0.0
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          sum += eta(i, j) - math.max(0.0, bed(i, j))
          i += 1
        j += 1
      sum * mac.h * mac.h
    else eta.sum * mac.h * mac.h

  /**
   * The energy per unit of density, `½ ∫ (g η² + h |u|²)`, in m⁵/s²: the potential energy of the
   * raised or lowered surface and the kinetic energy of the flow, with the depth `h` of the
   * water at the faces.
   */
  def energy: Double =
    val u = this.u
    val v = this.v
    var potential = 0.0
    var kinetic = 0.0
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        val e = eta(i, j)
        if flooding then
          val rest = math.max(0.0, bed(i, j))
          potential += e * e - rest * rest
        else potential += e * e
        if openU(i, j) != 0.0 then
          val west = if i > 0 then thickness(i - 1, j) else thickness(nx - 1, j)
          kinetic += 0.5 * (west + thickness(i, j)) * u(i, j) * u(i, j)
        if j > 0 && openV(i, j) != 0.0 then
          kinetic += 0.5 * (thickness(i, j - 1) + thickness(i, j)) * v(i, j) * v(i, j)
        i += 1
      j += 1
    0.5 * (gravityNow * potential + kinetic) * mac.h * mac.h

  /** The largest speed at a cell centre, in m/s. */
  def maxSpeed: Double =
    val u = this.u
    val v = this.v
    var m = 0.0
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        val a = 0.5 * (u(i, j) + u(i + 1, j))
        val b = 0.5 * (v(i, j) + v(i, j + 1))
        m = math.max(m, a * a + b * b)
        i += 1
      j += 1
    math.sqrt(m)

  /**
   * Shapes the sea floor, see [[ShallowParams.seabed]]: 1 is a shelf that is flat to 55% of the
   * width, then rises linearly to a twentieth of the depth at 90% and stays there; 2 is a
   * Gaussian seamount in the middle that takes away 80% of the depth at its top.
   */
  def seabed(kind: Int, slope: Double = Shallow.DefaultSlope): Unit =
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        shape(i, j) = kind match
          case 3 => beachShape(i + 0.5, slope)
          case 4 =>
            val dy = j + 0.5 - 0.5 * ny
            val sigma = Shallow.BayWidth * ny
            val ramp = math.min(1.0, math.max(0.0, (i + 0.5 - Shallow.BeachToe * nx) / (0.1 * nx)))
            beachShape(i + 0.5, slope) +
              Shallow.BayDepth * math.exp(-dy * dy / (2.0 * sigma * sigma)) * ramp
          case 1 =>
            val x = (i + 0.5) / nx
            if x <= 0.55 then 1.0
            else if x >= 0.9 then Shallow.ShelfDepth
            else 1.0 - (1.0 - Shallow.ShelfDepth) * (x - 0.55) / 0.35
          case 2 =>
            val sigma = 0.12 * math.min(nx, ny)
            val dx = i + 0.5 - 0.5 * nx
            val dy = j + 0.5 - 0.5 * ny
            1.0 - 0.8 * math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))
          case _ => 1.0
        i += 1
      j += 1

  /**
   * The depth at rest, as a fraction of the depth, of a plane beach at `x` cells from the left:
   * 1 up to [[Shallow.BeachToe]] of the width, then the floor rises by `slope` m per m, which
   * takes the fraction below 0 where the floor comes out of the water.
   */
  private def beachShape(x: Double, slope: Double): Double =
    val toe = Shallow.BeachToe * nx
    if x <= toe then 1.0 else 1.0 - slope * (x - toe) * mac.h / depthNow

  /** `sech²` of `x`, without overflow. */
  private def sech2(x: Double): Double =
    val c = math.cosh(x)
    if c.isInfinite then 0.0 else 1.0 / (c * c)

  /**
   * A solitary wave of height `amp` going right, standing at [[Shallow.WaveStart]] of the width:
   * `η = amp sech²((x - x0) / w)` with the speed of the water `u = c η / (h + η)`, `c = √(g (h + amp))`,
   * which is the solitary wave of the nonlinear equations when `w = √(4 h³ / 3 amp)` is wider than
   * a few cells ([[Shallow.waveWidth]] widens it to five cells otherwise).
   */
  def solitary(p: ShallowParams): Unit =
    if p.amp > 0.0 then
      val u = this.u
      val w = Shallow.waveWidth(p)
      val x0 = Shallow.WaveStart * nx * p.dx
      val c = math.sqrt(p.gravity * (p.depth + p.amp))
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          eta(i, j) = p.amp * sech2(((i + 0.5) * p.dx - x0) / w)
          i += 1
        i = 0
        while i <= nx do
          val e = p.amp * sech2((i * p.dx - x0) / w)
          u(i, j) = c * e / (p.depth + e)
          i += 1
        j += 1
      closeFaces()

  /**
   * An N-wave of height `amp` going right: a hump of `amp` with a dip of `amp` three widths ahead
   * of it, `η = amp (sech²((x - x0) / w) - sech²((x - x0 - 3 w) / w))`, with the speed of the water of a
   * linear wave, `u = η √(g / h)`. It floods nothing at first and brings the sea back from the
   * shore before the crest, as a tsunami can.
   */
  def nWave(p: ShallowParams): Unit =
    if p.amp > 0.0 then
      val u = this.u
      val w = Shallow.waveWidth(p)
      val x0 = Shallow.WaveStart * nx * p.dx
      val k = math.sqrt(p.gravity / p.depth)
      def height(x: Double): Double =
        p.amp * (sech2((x - x0) / w) - sech2((x - x0 - 3.0 * w) / w))
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          eta(i, j) = height((i + 0.5) * p.dx)
          i += 1
        i = 0
        while i <= nx do
          u(i, j) = k * height(i * p.dx)
          i += 1
        j += 1
      closeFaces()

  /**
   * A step in the height across the middle of the domain: `amp` in the northern half (the top
   * rows), 0 in the southern half, smoothed over a few cells, the same along `x`. The water is at rest.
   */
  def front(amp: Double): Unit =
    var j = 0
    while j < ny do
      val s = 0.5 * (1.0 - math.tanh((j + 0.5 - 0.5 * ny) / Shallow.FrontWidth))
      var i = 0
      while i < nx do
        eta(i, j) = amp * s
        i += 1
      j += 1

  /**
   * A Rossby wave, one wavelength along `x` and half a wave across `y`, of height `amp`:
   * `η = amp sin(k x) sin(l y)` with the flow in geostrophic balance with it, `f u = g ∂η/∂y`
   * and `f v = -g ∂η/∂x` (`y` runs down the picture). A wave that starts balanced mostly stays a Rossby wave and drifts
   * west; the gravity waves of an unbalanced start would hide it.
   */
  def rossby(p: ShallowParams): Unit =
    val u = this.u
    val v = this.v
    val h = p.dx
    val k = 2.0 * math.Pi / (nx * h)
    val l = math.Pi / (ny * h)
    var j = 0
    while j < ny do
      val yc = (j + 0.5) * h
      var i = 0
      while i < nx do
        eta(i, j) = p.amp * math.sin(k * (i + 0.5) * h) * math.sin(l * yc)
        i += 1
      val f = Shallow.coriolis(p, j + 0.5)
      if math.abs(f) > Shallow.MinCoriolis then
        i = 0
        while i <= nx do
          u(i, j) = p.gravity / f * p.amp * math.sin(k * i * h) * l * math.cos(l * yc)
          i += 1
      j += 1
    j = 0
    while j <= ny do
      val f = Shallow.coriolis(p, j.toDouble)
      if math.abs(f) > Shallow.MinCoriolis then
        var i = 0
        while i < nx do
          v(i, j) = -p.gravity / f * p.amp * k * math.cos(k * (i + 0.5) * h) * math.sin(l * j * h)
          i += 1
      j += 1
    closeFaces()

object ShallowState:
  /** Live runs export the height, the speed, the vorticity, the depth and the land. */
  given stateExport: StateExport[ShallowState] with
    def size(state: ShallowState): Int = 5 * state.mac.cells.cells

    def copyInto(state: ShallowState, out: Array[Double]): Unit =
      val n = state.mac.cells.cells
      state.heightInto(out, 0)
      state.speedInto(out, n)
      state.vorticityInto(out, 2 * n)
      state.depthInto(out, 3 * n)
      state.land.field.copyInteriorInto(out, 4 * n)

    override def layout(state: ShallowState): IndexedSeq[Slice] =
      val g = state.mac.cells
      val n = g.cells
      Vector(
        Slice("height", 0, g.nx, g.ny),
        Slice("speed", n, g.nx, g.ny),
        Slice("vorticity", 2 * n, g.nx, g.ny),
        Slice("depth", 3 * n, g.nx, g.ny),
        Slice("land", 4 * n, g.nx, g.ny)
      )

object Shallow extends Model[ShallowParams]:
  type State = ShallowState
  val id = "shallow"
  val version = "0.8.0"
  val params: ParamSchema[ShallowParams] = ShallowParams.schema

  /** The step the model is meant for, in seconds. */
  val Dt: Double = 50.0

  val stepping: Stepping = Stepping.Fixed(Dt)

  /** What an absorbing strip removes of the height and the velocity per step, at the border. */
  val SpongeStrength: Double = 0.1

  /** A Coriolis parameter below this counts as 0 (no balanced flow is set up for it). */
  val MinCoriolis: Double = 1e-9

  /**
   * The Coriolis parameter `f = f0 + β (y_mid - y)` at row `row` (counted from the top, which
   * is the north; `ny / 2` is the middle), in 1/s. It grows toward the north, up the picture.
   */
  def coriolis(p: ShallowParams, row: Double): Double =
    p.f0 + p.beta * (0.5 * p.ny - row) * p.dx

  /** The largest `|f|` anywhere on the plane, in 1/s. */
  def maxCoriolis(p: ShallowParams): Double =
    math.max(
      math.abs(coriolis(p, 0.0)),
      math.max(math.abs(coriolis(p, p.ny.toDouble)), math.abs(p.f0))
    )

  /** The largest `f dt` or `r dt` the step allows. */
  val MaxRateNumber: Double = 0.5

  /** The depth over the top of the shelf of seabed 1, as a fraction of the depth. */
  val ShelfDepth: Double = 0.05

  /** The slope of the beach of the sea floors 3 and 4 when none is given, in m per m. */
  val DefaultSlope: Double = 0.05

  /** Where the beach starts to rise, as a fraction of the width. */
  val BeachToe: Double = 0.5

  /** Where the wave of the starts 3 and 4 stands, as a fraction of the width. */
  val WaveStart: Double = 0.25

  /** How much deeper the bay of the sea floor 4 is than the beach, as a fraction of the depth. */
  val BayDepth: Double = 0.3

  /** The width of the bay of the sea floor 4, as a fraction of the number of rows. */
  val BayWidth: Double = 0.1

  /**
   * The width `w` of the waves of the starts 3 and 4, in m: that of a solitary wave of height
   * `amp`, `√(4 h³ / (3 amp))`, but never under five cells, which the grid could not carry.
   */
  def waveWidth(p: ShallowParams): Double =
    math.max(math.sqrt(4.0 * p.depth * p.depth * p.depth / (3.0 * p.amp)), 5.0 * p.dx)

  /** Width of the smoothed front of [[ShallowState.front]], in cells. */
  val FrontWidth: Double = 3.0

  /**
   * The largest `c dt / dx` the forward–backward step allows in two dimensions (`1 / √2` is the
   * limit; this keeps a margin).
   */
  val MaxCourant: Double = 0.7

  /** The borders of [[ShallowParams.edges]]. */
  val Channel = 0
  val Basin = 1
  val OpenSea = 2

  /** The speed of the gravity waves in water of the given depth, `√(g h)`, in m/s. */
  def waveSpeed(p: ShallowParams): Double = math.sqrt(p.gravity * p.depth)

  /** `c dt / dx` for a step of size `dt`. */
  def courant(p: ShallowParams, dt: Double): Double = waveSpeed(p) * dt / p.dx

  override def check(p: ShallowParams, stepping: Stepping): Either[String, Unit] =
    stepping match
      case Stepping.Fixed(dt) =>
        val c = courant(p, dt)
        if p.seabed >= 3 && p.flooding == 0 then
          Left("the sea floors 3 and 4 rise out of the water: they need flooding = 1")
        else if p.flooding != 0 && p.nonlinear == 0 then
          Left(
            "flooding needs the nonlinear equations (nonlinear = 1): a linear sea has no depth to dry out"
          )
        else if c > MaxCourant then
          Left(
            f"the waves cross $c%.2f cells per step; the step needs at most $MaxCourant%.1f " +
              f"(lower the depth or gravity, make the cells larger, or use dt <= ${MaxCourant * p.dx / waveSpeed(p)}%.0f s)"
          )
        else if maxCoriolis(p) * dt > MaxRateNumber then
          Left(
            f"f dt = ${maxCoriolis(p) * dt}%.2f is too large for the rotation; the limit is $MaxRateNumber%.1f"
          )
        else if p.friction * dt > MaxRateNumber then
          Left(
            f"friction dt = ${p.friction * dt}%.2f is too large; the limit is $MaxRateNumber%.1f"
          )
        else Right(())
      case _: Stepping.Adaptive => Right(())

  override def supports(stepping: Stepping): Boolean = stepping match
    case _: Stepping.Fixed => true
    case _: Stepping.Adaptive => false

  def init(p: ShallowParams, rng: SplittableRng): ShallowState =
    val state = new ShallowState(new MacGrid(p.nx, p.ny, p.dx), wrapX = p.edges == Channel)
    state.configure(p)
    state.seabed(p.seabed, p.slope)
    if p.edges == OpenSea then state.sponge(p.sponge)
    p.start match
      case 1 => state.front(p.amp)
      case 2 => state.rossby(p)
      case 3 => state.solitary(p)
      case 4 => state.nWave(p)
      case _ => ()
    state.settle()
    state

  def advance(state: ShallowState, p: ShallowParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: ShallowState): Long =
    state.land.field.digestInto(
      state.v.digestInto(state.u.digestInto(state.eta.digestInto(Digest.start)))
    )

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Brush("land" | "height", _, _, _) => Right(())
    case Intervention.Pulse("height", _, _, _, _) => Right(())
    case Intervention.Brush(target, _, _, _) =>
      Left(s"shallow water has no field '$target' to paint (use 'land' or 'height')")
    case Intervention.Pulse(target, _, _, _, _) =>
      Left(s"shallow water has no field '$target' to pulse (use 'height')")
    case _: Intervention.Disturb => Left("shallow water has no agents to disturb")

  /**
   * `Brush("land")` paints land (the water in those cells is lost with them) or, with `Erase`,
   * sea; `Brush("height")` sets or adds to the height of the surface; `Pulse("height")` raises
   * or lowers it by a Gaussian. Land holds no water, so height painted on land is dropped.
   */
  override def intervene(
      state: ShallowState,
      p: ShallowParams,
      iv: Intervention
  ): Either[String, Unit] =
    accepts(iv).map { _ =>
      iv match
        case Intervention.Brush(target, disc, op, value) =>
          state.applyBrush(target, disc, op, value)
        case Intervention.Pulse(_, x, y, sigma, amplitude) =>
          state.applyPulse(x, y, sigma, amplitude)
        case _ => ()
    }

  /** The volume of water above rest, in m³: constant unless something is painted or absorbed. */
  val mass: Probe[ShallowState] = Probe[ShallowState]("mass", "m³")((s, _) => s.volume)

  /** The energy per unit of density, in m⁵/s². */
  val energy: Probe[ShallowState] = Probe[ShallowState]("energy", "m⁵/s²")((s, _) => s.energy)

  /** The highest the surface stands above rest, in m. */
  val maxHeight: Probe[ShallowState] =
    Probe[ShallowState]("max_height", "m")((s, _) => s.eta.max)

  /** The lowest the surface stands below rest (negative), in m. */
  val minHeight: Probe[ShallowState] =
    Probe[ShallowState]("min_height", "m")((s, _) => s.eta.min)

  /** The fastest the water moves, in m/s. */
  val maxSpeed: Probe[ShallowState] =
    Probe[ShallowState]("max_speed", "m/s")((s, _) => s.maxSpeed)

  val probes: Vector[Probe[ShallowState]] =
    Vector(mass, energy, maxHeight, minHeight, maxSpeed)
