package physis.models.grayscott

import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec, Preset}
import physis.core.random.SplittableRng
import physis.fields.{Boundaries, Field2D, Grid2D, Stencils}

/**
 * Parameters of the Gray-Scott model.
 *
 * @param Du    diffusion rate of u
 * @param Dv    diffusion rate of v
 * @param F     feed rate: how fast u is replenished
 * @param k     kill rate: how fast v is removed
 * @param nx    grid columns (needs a restart)
 * @param ny    grid rows (needs a restart)
 * @param noise amplitude of the random perturbation of the initial seed (needs a restart)
 */
final case class GrayScottParams(
    Du: Double,
    Dv: Double,
    F: Double,
    k: Double,
    nx: Int,
    ny: Int,
    noise: Double
)

object GrayScottParams:
  given schema: ParamSchema[GrayScottParams] = ParamSchema.derived[GrayScottParams](
    ParamSpec("Du", default = 1.0, min = 0.0, max = 1.25, doc = "Diffusion rate of u"),
    ParamSpec("Dv", default = 0.5, min = 0.0, max = 1.25, doc = "Diffusion rate of v"),
    ParamSpec("F", default = 0.0367, min = 0.0, max = 0.1, doc = "Feed rate"),
    ParamSpec("k", default = 0.0649, min = 0.0, max = 0.1, doc = "Kill rate"),
    ParamSpec("nx", default = 256, min = 32, max = 1024, doc = "Grid columns", restart = true),
    ParamSpec("ny", default = 256, min = 32, max = 1024, doc = "Grid rows", restart = true),
    ParamSpec(
      "noise",
      default = 0.01,
      min = 0.0,
      max = 0.1,
      doc = "Perturbation of the initial seed",
      restart = true
    )
  )

/**
 * State of a Gray-Scott run: the two concentration fields and the scratch fields that hold
 * their Laplacians, so a step allocates nothing.
 */
final class GrayScottState(val grid: Grid2D):
  val u: Field2D = new Field2D(grid)
  val v: Field2D = new Field2D(grid)
  private val lu: Field2D = new Field2D(grid)
  private val lv: Field2D = new Field2D(grid)

  /**
   * One forward Euler step of size `dt` on the periodic grid: fill the halos, take both
   * 9-point Laplacians, then update every cell from its own old values.
   *
   * Per cell, in this order of operations (a reference implementation can repeat it exactly):
   * {{{
   * uvv = (u * v) * v
   * du  = (Du * lu - uvv) + F * (1 - u)
   * dv  = (Dv * lv + uvv) - (F + k) * v
   * u   = u + dt * du
   * v   = v + dt * dv
   * }}}
   */
  def step(du: Double, dv: Double, f: Double, k: Double, dt: Double): Unit =
    laplacians()
    val ud = u.data
    val vd = v.data
    val lud = lu.data
    val lvd = lv.data
    val fk = f + k
    var j = 0
    while j < grid.ny do
      var i = grid.index(0, j)
      val end = i + grid.nx
      while i < end do
        val uk = ud(i)
        val vk = vd(i)
        val uvv = uk * vk * vk
        val dUk = (du * lud(i) - uvv) + f * (1.0 - uk)
        val dVk = (dv * lvd(i) + uvv) - fk * vk
        ud(i) = uk + dt * dUk
        vd(i) = vk + dt * dVk
        i += 1
      j += 1

  /**
   * The diffusion part of a step alone, `u += dt * Du * lu` and the same for v, without the
   * reaction terms. It exists to verify the stencil, the boundaries and the time stepping
   * against closed forms and conservation laws.
   */
  def diffuseOnly(du: Double, dv: Double, dt: Double): Unit =
    laplacians()
    val ud = u.data
    val vd = v.data
    val lud = lu.data
    val lvd = lv.data
    var j = 0
    while j < grid.ny do
      var i = grid.index(0, j)
      val end = i + grid.nx
      while i < end do
        ud(i) = ud(i) + dt * (du * lud(i))
        vd(i) = vd(i) + dt * (dv * lvd(i))
        i += 1
      j += 1

  private def laplacians(): Unit =
    Boundaries.periodic.fill(u)
    Boundaries.periodic.fill(v)
    Stencils.laplacian9(u, lu)
    Stencils.laplacian9(v, lv)

  /** Fraction of cells whose v exceeds `threshold`. */
  def coverage(threshold: Double): Double =
    val vd = v.data
    var count = 0
    var j = 0
    while j < grid.ny do
      var i = grid.index(0, j)
      val end = i + grid.nx
      while i < end do
        if vd(i) > threshold then count += 1
        i += 1
      j += 1
    count.toDouble / grid.cells

object GrayScottState:
  /** Live runs export u then v, as two slices of the grid's size. */
  given stateExport: StateExport[GrayScottState] with
    def size(state: GrayScottState): Int = 2 * state.grid.cells

    def copyInto(state: GrayScottState, out: Array[Double]): Unit =
      state.u.copyInteriorInto(out, 0)
      state.v.copyInteriorInto(out, state.grid.cells)

    override def layout(state: GrayScottState): IndexedSeq[Slice] =
      val g = state.grid
      Vector(Slice("u", 0, g.nx, g.ny), Slice("v", g.cells, g.nx, g.ny))

/**
 * The Gray-Scott reaction-diffusion model (Pearson 1993):
 *
 * {{{
 * du/dt = Du lap(u) - u v^2 + F (1 - u)
 * dv/dt = Dv lap(v) + u v^2 - (F + k) v
 * }}}
 *
 * on a periodic grid with unit cell size, advanced by forward Euler with the isotropic 9-point
 * Laplacian. Time is measured in steps: the usual step is `dt = 1` ([[stepping]]).
 *
 * Initial state: u = 1 and v = 0 everywhere, except a centred square (side one tenth of the
 * smaller grid side, at least 2 cells) with u = 0.5 and v = 0.25, each perturbed by up to
 * `noise / 2` from the run's seeded stream. The same seed gives the same pattern on every machine.
 *
 * Forward Euler is stable for the diffusion terms only while `dt * max(Du, Dv) <= 1.25` (the
 * 9-point stencil's most negative eigenvalue is -1.6), which [[check]] enforces.
 */
object GrayScott extends Model[GrayScottParams]:
  type State = GrayScottState
  val id = "gray-scott"
  val version = "0.3.0"
  val params: ParamSchema[GrayScottParams] = GrayScottParams.schema

  /** The step the model is meant for: forward Euler with `dt = 1`. */
  val stepping: Stepping = Stepping.Fixed(1.0)

  /** Largest `dt * D` for which forward Euler with the 9-point stencil is stable: 2 / 1.6. */
  val StabilityLimit: Double = 1.25

  override def check(p: GrayScottParams, stepping: Stepping): Either[String, Unit] =
    stepping match
      case Stepping.Fixed(dt) =>
        val worst = dt * math.max(p.Du, p.Dv)
        if worst <= StabilityLimit then Right(())
        else
          Left(
            s"dt * max(Du, Dv) = $worst exceeds the stability limit $StabilityLimit of the " +
              "explicit scheme; lower Du or Dv, or use a smaller step"
          )
      case _: Stepping.Adaptive => Right(())

  def init(p: GrayScottParams, rng: SplittableRng): GrayScottState =
    val state = new GrayScottState(Grid2D(p.nx, p.ny))
    state.u.fill(1.0)
    state.v.fill(0.0)
    val side = math.max(2, math.min(p.nx, p.ny) / 10)
    val x0 = (p.nx - side) / 2
    val y0 = (p.ny - side) / 2
    var j = y0
    while j < y0 + side do
      var i = x0
      while i < x0 + side do
        state.u(i, j) = 0.5 + p.noise * (rng.nextDouble() - 0.5)
        state.v(i, j) = 0.25 + p.noise * (rng.nextDouble() - 0.5)
        i += 1
      j += 1
    state

  def advance(state: GrayScottState, p: GrayScottParams, dt: Double, ctx: StepContext): Unit =
    state.step(p.Du, p.Dv, p.F, p.k, dt)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: GrayScottState): Long =
    state.v.digestInto(state.u.digestInto(Digest.start))

  /** Mean of u over the grid. */
  val meanU: Probe[GrayScottState] = Probe[GrayScottState]("u_mean")((s, _) => s.u.mean)

  /** Mean of v over the grid. */
  val meanV: Probe[GrayScottState] = Probe[GrayScottState]("v_mean")((s, _) => s.v.mean)

  /** Largest v on the grid. */
  val maxV: Probe[GrayScottState] = Probe[GrayScottState]("v_max")((s, _) => s.v.max)

  /** Fraction of cells with v > 0.2: how much of the grid the pattern covers. */
  val coverage: Probe[GrayScottState] = Probe[GrayScottState]("coverage")((s, _) => s.coverage(0.2))

  val probes: Vector[Probe[GrayScottState]] = Vector(meanU, meanV, maxV, coverage)

  /** Named regimes from Pearson's classification of the (F, k) plane. */
  val presets: Vector[Preset] = Vector(
    Preset("Mitosis", Map("F" -> 0.0367, "k" -> 0.0649), "Spots that grow and divide"),
    Preset("Coral", Map("F" -> 0.0545, "k" -> 0.0620), "Coral-like growth"),
    Preset("Maze", Map("F" -> 0.0290, "k" -> 0.0570), "Labyrinth of stripes"),
    Preset("Solitons", Map("F" -> 0.0300, "k" -> 0.0620), "Pulsating solitons"),
    Preset("Worms", Map("F" -> 0.0780, "k" -> 0.0610), "Wandering worms")
  )
