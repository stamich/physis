package physis.models.shallow

import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng
import physis.fields.{Field2D, Mac, MacGrid, Mask, Staggered}

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
 * @param edges   the borders (needs a restart): 0 a channel, wrapping left and right, with walls
 *                above and below; 1 a closed basin; 2 an open sea whose borders absorb waves
 * @param sponge  width in cells of the absorbing strip along the borders of an open sea
 * @param start   how the run begins (needs a restart): 0 at rest, 1 a front, 2 a Rossby wave
 * @param amp     the size of the start, in m: the height of the front
 * @param nx      grid columns (needs a restart)
 * @param ny      grid rows (needs a restart)
 */
final case class ShallowParams(
    gravity: Double,
    depth: Double,
    dx: Double,
    f0: Double,
    beta: Double,
    friction: Double,
    edges: Int,
    sponge: Int,
    start: Int,
    amp: Double,
    nx: Int,
    ny: Int
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
      min = 500.0,
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
      "start",
      default = 0,
      min = 0,
      max = 2,
      doc = "How the run begins: 0 at rest, 1 a step in the height, 2 a Rossby wave",
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
    ParamSpec("ny", default = 96, min = 16, max = 512, doc = "Grid rows", restart = true)
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

  /** The horizontal velocity, on the vertical faces, in m/s. */
  val u: Field2D = new Field2D(mac.uFaces)

  /** The vertical velocity, on the horizontal faces, in m/s. */
  val v: Field2D = new Field2D(mac.vFaces)

  /** The depth of the water at rest, in m. */
  val depth: Field2D = new Field2D(mac.cells)

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

  /** Land holds no water. */
  def clearLand(): Unit =
    if land.count > 0 then
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          if land(i, j) then eta(i, j) = 0.0
          i += 1
        j += 1

  /** The depth of the water in every cell: the depth at rest, in this version. */
  private def refreshTotal(): Unit =
    total.copyFrom(depth)

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
      var j = 0
      while j < ny do
        var i = 0
        while i < nx do
          eta(i, j) *= keepC(i, j)
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

  /** The velocity equations: the slope of the surface and the rotation, from the new height. */
  private def momentum(p: ShallowParams, dt: Double): Unit =
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

  /** One step of size `dt`. */
  def step(p: ShallowParams, dt: Double): Unit =
    continuity(dt)
    momentum(p, dt)
    absorb()

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
        if c > MaxCourant then
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
    state.depth.fill(p.depth)
    if p.edges == OpenSea then state.sponge(p.sponge)
    p.start match
      case 1 => state.front(p.amp)
      case 2 => state.rossby(p)
      case _ => ()
    state

  def advance(state: ShallowState, p: ShallowParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: ShallowState): Long =
    state.land.field.digestInto(
      state.v.digestInto(state.u.digestInto(state.eta.digestInto(Digest.start)))
    )

  val probes: Vector[Probe[ShallowState]] = Vector.empty
