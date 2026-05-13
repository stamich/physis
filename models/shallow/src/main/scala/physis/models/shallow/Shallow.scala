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
 * @param f0      the Coriolis parameter `f = 2 Ω sin φ` of the rotating plane, in 1/s
 * @param start   how the run begins (needs a restart): 0 at rest, 1 a front
 * @param amp     the size of the start, in m: the height of the front
 * @param nx      grid columns (needs a restart)
 * @param ny      grid rows (needs a restart)
 */
final case class ShallowParams(
    gravity: Double,
    depth: Double,
    dx: Double,
    f0: Double,
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
      "start",
      default = 0,
      min = 0,
      max = 1,
      doc = "How the run begins: 0 at rest, 1 a step in the height across the middle",
      restart = true
    ),
    ParamSpec(
      "amp",
      default = 0.2,
      min = 0.0,
      max = 20.0,
      unit = "m",
      doc = "Size of the start: the height of the step",
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
 *   ∂u/∂t = -g ∂η/∂x + f v
 *   ∂v/∂t = -g ∂η/∂y - f u
 * }}}
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
    val f = p.f0
    Staggered.vToU(v, vAtU, wrapX)
    var j = 0
    while j < ny do
      var i = 0
      while i < nx do
        if openU(i, j) != 0.0 then
          val west = if i > 0 then eta(i - 1, j) else eta(nx - 1, j)
          u(i, j) += dt * (-g * (eta(i, j) - west) * inv + f * vAtU(i, j))
        i += 1
      if wrapX then u(nx, j) = u(0, j)
      j += 1
    Staggered.uToV(u, uAtV)
    j = 1
    while j < ny do
      var i = 0
      while i < nx do
        if openV(i, j) != 0.0 then
          v(i, j) += dt * (-g * (eta(i, j) - eta(i, j - 1)) * inv - f * uAtV(i, j))
        i += 1
      j += 1

  /** One step of size `dt`. */
  def step(p: ShallowParams, dt: Double): Unit =
    continuity(dt)
    momentum(p, dt)

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

object Shallow extends Model[ShallowParams]:
  type State = ShallowState
  val id = "shallow"
  val version = "0.8.0"
  val params: ParamSchema[ShallowParams] = ShallowParams.schema

  /** The step the model is meant for, in seconds. */
  val Dt: Double = 50.0

  val stepping: Stepping = Stepping.Fixed(Dt)

  /** Width of the smoothed front of [[ShallowState.front]], in cells. */
  val FrontWidth: Double = 3.0

  /**
   * The largest `c dt / dx` the forward–backward step allows in two dimensions (`1 / √2` is the
   * limit; this keeps a margin).
   */
  val MaxCourant: Double = 0.7

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
        else Right(())
      case _: Stepping.Adaptive => Right(())

  override def supports(stepping: Stepping): Boolean = stepping match
    case _: Stepping.Fixed => true
    case _: Stepping.Adaptive => false

  def init(p: ShallowParams, rng: SplittableRng): ShallowState =
    val state = new ShallowState(new MacGrid(p.nx, p.ny, p.dx), wrapX = false)
    state.depth.fill(p.depth)
    p.start match
      case 1 => state.front(p.amp)
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
