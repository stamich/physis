package physis.models.physarum

import physis.agents.ParticleSet
import physis.core.{Digest, Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec, Preset}
import physis.core.random.SplittableRng
import physis.core.run.{Disc, Intervention, Op}
import physis.fields.{Brush, Deposit, Field2D, Grid2D, Pulse, Sampler, TrailOps}

/**
 * Parameters of the Physarum model.
 *
 * @param sensorAngle angle between the ahead sensor and each side sensor, in radians
 * @param sensorDist  distance of the sensors from the agent, in cells
 * @param turnAngle   angle an agent turns by in one step, in radians
 * @param stepSize    distance an agent moves per step, in cells
 * @param deposit     trail an agent leaves in the cell it ends its step in
 * @param decay       share of the trail that fades per step
 * @param diffusion   share of the trail replaced by its 3 x 3 mean per step
 * @param foodWeight  weight of the food field against the trail in what the sensors read
 * @param n           number of agents (needs a restart)
 * @param nx          grid columns (needs a restart)
 * @param ny          grid rows (needs a restart)
 */
final case class PhysarumParams(
    sensorAngle: Double,
    sensorDist: Double,
    turnAngle: Double,
    stepSize: Double,
    deposit: Double,
    decay: Double,
    diffusion: Double,
    foodWeight: Double,
    n: Int,
    nx: Int,
    ny: Int
)

object PhysarumParams:
  given schema: ParamSchema[PhysarumParams] = ParamSchema.derived[PhysarumParams](
    ParamSpec("sensorAngle", 0.7, 0.1, 1.5, unit = "rad", doc = "Side sensor angle"),
    ParamSpec("sensorDist", 9.0, 1.0, 30.0, unit = "cells", doc = "Sensor distance"),
    ParamSpec("turnAngle", 0.6, 0.05, 1.5, unit = "rad", doc = "Turn per step"),
    ParamSpec("stepSize", 1.0, 0.2, 3.0, unit = "cells", doc = "Move per step"),
    ParamSpec("deposit", 5.0, 0.1, 50.0, doc = "Trail left per agent and step"),
    ParamSpec("decay", 0.1, 0.0, 0.5, unit = "1/step", doc = "Share of trail that fades"),
    ParamSpec("diffusion", 1.0, 0.0, 1.0, doc = "Share of trail spread over 3 x 3 cells"),
    ParamSpec("foodWeight", 1.0, 0.0, 10.0, doc = "Weight of food against trail"),
    ParamSpec("n", 40000, 10, 1000000, doc = "Number of agents", restart = true),
    ParamSpec("nx", 256, 64, 1024, doc = "Grid columns", restart = true),
    ParamSpec("ny", 256, 64, 1024, doc = "Grid rows", restart = true)
  )

/**
 * State of a Physarum run: the agents (position and heading as a unit vector), the trail at two
 * time levels (swapped by reference), and the food. A step allocates nothing.
 */
final class PhysarumState(val n: Int, val grid: Grid2D, val noiseKey: Long):
  val particles: ParticleSet = ParticleSet(n, "x", "y", "dx", "dy")
  val x: Array[Double] = particles.column("x")
  val y: Array[Double] = particles.column("y")
  val dx: Array[Double] = particles.column("dx")
  val dy: Array[Double] = particles.column("dy")

  private var now: Field2D = new Field2D(grid)
  private var next: Field2D = new Field2D(grid)

  /** What agents are drawn to besides the trail. Strokes paint it. */
  val food: Field2D = new Field2D(grid)

  // Scratch of the structure count; made on first use.
  private var mark: Array[Byte] = new Array[Byte](0)
  private var stack: Array[Int] = new Array[Int](0)

  /** The trail. */
  def trail: Field2D = now

  /**
   * One step, in this order:
   *  1. every agent reads three sensors (left, ahead, right) from the trail as it stands, plus
   *     `foodWeight` times the food there, and turns: towards the strongest sensor, by a random
   *     turn to either side when ahead is weakest, not at all when the sensors do not decide;
   *  2. every agent moves one step and wraps;
   *  3. agents deposit into the trail one after another in index order;
   *  4. the trail spreads (3 x 3 mean) and fades.
   *
   * Nothing writes the trail before every agent has sensed, so sensing does not depend on the
   * order of the agents; depositing in index order makes the sums repeatable.
   */
  def step(p: PhysarumParams, stepIndex: Long): Unit =
    val cs = Noise.cos(p.sensorAngle)
    val ss = Noise.sin(p.sensorAngle)
    val ct = Noise.cos(p.turnAngle)
    val st = Noise.sin(p.turnAngle)
    val trailNow = now
    val foodNow = food
    val useFood = p.foodWeight != 0.0
    val w = p.foodWeight
    val d = p.sensorDist
    var i = 0
    while i < n do
      val px = x(i)
      val py = y(i)
      val ux = dx(i)
      val uy = dy(i)
      // Left is the heading turned by +sensorAngle, right by -sensorAngle.
      val lx = px + d * (ux * cs - uy * ss)
      val ly = py + d * (ux * ss + uy * cs)
      val rx = px + d * (ux * cs + uy * ss)
      val ry = py + d * (uy * cs - ux * ss)
      val ax = px + d * ux
      val ay = py + d * uy
      var left = Sampler.nearest(trailNow, lx, ly)
      var ahead = Sampler.nearest(trailNow, ax, ay)
      var right = Sampler.nearest(trailNow, rx, ry)
      if useFood then
        left += w * Sampler.nearest(foodNow, lx, ly)
        ahead += w * Sampler.nearest(foodNow, ax, ay)
        right += w * Sampler.nearest(foodNow, rx, ry)
      // turn: +1 left, -1 right, 0 keep
      var turn = 0
      if ahead > left && ahead > right then turn = 0
      else if ahead < left && ahead < right then
        turn = if Noise.uniform(noiseKey, stepIndex, i) < 0.5 then 1 else -1
      else if left < right then turn = -1
      else if right < left then turn = 1
      var hx = ux
      var hy = uy
      if turn != 0 then
        val s = if turn > 0 then st else -st
        val tx = ux * ct - uy * s
        val ty = ux * s + uy * ct
        val inv = 1.0 / math.sqrt(tx * tx + ty * ty)
        hx = tx * inv
        hy = ty * inv
      dx(i) = hx
      dy(i) = hy
      x(i) = Sampler.wrap(px + p.stepSize * hx, grid.nx)
      y(i) = Sampler.wrap(py + p.stepSize * hy, grid.ny)
      i += 1
    i = 0
    while i < n do
      Deposit.nearest(now, x(i), y(i), p.deposit)
      i += 1
    if p.diffusion > 0.0 then
      TrailOps.diffuse(now, next, p.diffusion)
      val swap = now
      now = next
      next = swap
    TrailOps.decay(now, 1.0 - p.decay)

  /** Total trail. */
  def mass: Double = now.sum

  private def fieldNamed(target: String): Field2D = if target == "food" then food else now

  /** Paints food or trail on the periodic grid; the field never goes below zero. */
  def applyBrush(target: String, disc: Disc, op: Op, value: Double): Unit =
    val _ = Brush(fieldNamed(target), disc, true, true, op, value, lo = 0.0)

  /** Adds a Gaussian bump of food or trail. */
  def applyPulse(target: String, x: Double, y: Double, sigma: Double, amplitude: Double): Unit =
    val _ = Pulse.stamp(fieldNamed(target), x, y, sigma, amplitude, true, true)

  private var covered = 0
  private var components = 0

  /**
   * Counts the cells with more than twice the mean trail and the connected pieces (4-connected,
   * wrapping at the border) they form. The level follows the amount of trail, so it does not
   * depend on the number of agents or the size of the grid.
   */
  private def measureStructure(): Unit =
    val nx = grid.nx
    val ny = grid.ny
    val cells = grid.cells
    if mark.length != cells then
      mark = new Array[Byte](cells)
      stack = new Array[Int](cells)
    java.util.Arrays.fill(mark, 0.toByte)
    val level = 2.0 * now.mean
    val d = now.data
    var count = 0
    var cover = 0
    var j0 = 0
    while j0 < ny do
      var i0 = 0
      while i0 < nx do
        val c0 = j0 * nx + i0
        if mark(c0) == 0 && d(grid.index(i0, j0)) > level then
          count += 1
          mark(c0) = 1
          stack(0) = c0
          var top = 1
          while top > 0 do
            top -= 1
            val c = stack(top)
            cover += 1
            val ci = c % nx
            val cj = c / nx
            val west = cj * nx + (if ci == 0 then nx - 1 else ci - 1)
            val east = cj * nx + (if ci == nx - 1 then 0 else ci + 1)
            val south = (if cj == 0 then ny - 1 else cj - 1) * nx + ci
            val north = (if cj == ny - 1 then 0 else cj + 1) * nx + ci
            var k = 0
            while k < 4 do
              val c2 = k match
                case 0 => west
                case 1 => east
                case 2 => south
                case _ => north
              if mark(c2) == 0 && d(grid.index(c2 % nx, c2 / nx)) > level then
                mark(c2) = 1
                stack(top) = c2
                top += 1
              k += 1
        i0 += 1
      j0 += 1
    covered = cover
    components = count

  /** Share of cells with more than twice the mean trail. */
  def coverage: Double =
    measureStructure()
    covered.toDouble / grid.cells

  /** Number of connected pieces of those cells. */
  def structures: Int =
    measureStructure()
    components

  /** Fingerprint of trail, food and agents. */
  def digest: Long =
    var h = food.digestInto(now.digestInto(Digest.start))
    var k = 0
    while k < 4 do
      h = Digest.fold(h, particles.columnAt(k), 0, n)
      k += 1
    h

object PhysarumState:
  /** Live runs export the trail, the food, then x, y, dx, dy of the agents as rows of n. */
  given stateExport: StateExport[PhysarumState] with
    def size(state: PhysarumState): Int = 2 * state.grid.cells + state.particles.size

    def copyInto(state: PhysarumState, out: Array[Double]): Unit =
      val cells = state.grid.cells
      state.trail.copyInteriorInto(out, 0)
      state.food.copyInteriorInto(out, cells)
      state.particles.copyInto(out, 2 * cells)

    override def layout(state: PhysarumState): IndexedSeq[Slice] =
      val g = state.grid
      Vector(Slice("trail", 0, g.nx, g.ny), Slice("food", g.cells, g.nx, g.ny)) ++
        state.particles.slices(2 * g.cells)

/**
 * Physarum (Jones 2010): agents on a periodic grid that follow a trail they lay themselves.
 * See [[PhysarumState.step]] for the order of a step. One step is one time unit.
 */
object Physarum extends Model[PhysarumParams]:
  type State = PhysarumState
  val id = "physarum"
  val version = "0.5.0"
  val params: ParamSchema[PhysarumParams] = PhysarumParams.schema

  val stepping: Stepping = Stepping.Fixed(1.0)

  override def check(p: PhysarumParams, stepping: Stepping): Either[String, Unit] =
    val smaller = math.min(p.nx, p.ny)
    if 2.0 * p.sensorDist >= smaller then
      Left(s"sensorDist (${p.sensorDist}) must be less than half the smaller side ($smaller)")
    else if p.stepSize > p.sensorDist then
      Left(s"stepSize (${p.stepSize}) must not exceed sensorDist (${p.sensorDist})")
    else Right(())

  def init(p: PhysarumParams, rng: SplittableRng): PhysarumState =
    val state = new PhysarumState(p.n, Grid2D(p.nx, p.ny), rng.nextLong())
    var i = 0
    while i < p.n do
      state.x(i) = rng.nextDouble() * p.nx
      state.y(i) = rng.nextDouble() * p.ny
      val heading = 2.0 * math.Pi * rng.nextDouble()
      state.dx(i) = StrictMath.cos(heading)
      state.dy(i) = StrictMath.sin(heading)
      i += 1
    state

  def advance(state: PhysarumState, p: PhysarumParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, ctx.step)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: PhysarumState): Long = state.digest

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Brush("food" | "trail", _, _, _) => Right(())
    case Intervention.Pulse("food" | "trail", _, _, _, amplitude) =>
      if amplitude >= 0.0 then Right(())
      else Left(s"pulse amplitude must not be negative (a field stays at or above 0): $amplitude")
    case Intervention.Brush(target, _, _, _) =>
      Left(s"physarum has no field '$target' to paint (use 'food' or 'trail')")
    case Intervention.Pulse(target, _, _, _, _) =>
      Left(s"physarum has no field '$target' to pulse (use 'food' or 'trail')")
    case _: Intervention.Disturb =>
      Left("physarum agents are not disturbed; paint 'food' or 'trail' instead")
    case _: Intervention.Load => Left("physarum reads no files")

  /**
   * Brush paints food or trail (set, add or erase; results are kept at or above zero) and Pulse
   * adds a non-negative Gaussian bump to either. Both use the periodic grid and no random
   * numbers, so painted sessions replay exactly.
   */
  override def intervene(
      state: PhysarumState,
      p: PhysarumParams,
      iv: Intervention
  ): Either[String, Unit] =
    accepts(iv).map { _ =>
      iv match
        case Intervention.Brush(target, disc, op, value) =>
          state.applyBrush(target, disc, op, value)
        case Intervention.Pulse(target, x, y, sigma, amplitude) =>
          state.applyPulse(target, x, y, sigma, amplitude)
        case _ => ()
    }

  /** Total trail. */
  val trailMass: Probe[PhysarumState] = Probe[PhysarumState]("trail_mass")((s, _) => s.mass)

  /** Share of cells with more than twice the mean trail. */
  val coverage: Probe[PhysarumState] = Probe[PhysarumState]("coverage")((s, _) => s.coverage)

  /** Number of connected pieces of those cells. */
  val structures: Probe[PhysarumState] =
    Probe[PhysarumState]("structures")((s, _) => s.structures.toDouble)

  val probes: Vector[Probe[PhysarumState]] = Vector(trailMass, coverage, structures)

  val presets: Vector[Preset] = Vector(
    Preset(
      "Network",
      Map(
        "sensorAngle" -> 0.7,
        "sensorDist" -> 9.0,
        "turnAngle" -> 0.6,
        "decay" -> 0.1
      ),
      "Jones' defaults: veins that join into one connected network"
    ),
    Preset(
      "Mesh",
      Map(
        "sensorAngle" -> 1.0,
        "sensorDist" -> 20.0,
        "turnAngle" -> 0.9,
        "decay" -> 0.05
      ),
      "Long, wide sensors and slow fading: a broad mesh that covers more of the world"
    ),
    Preset(
      "Spots",
      Map(
        "sensorAngle" -> 1.5,
        "sensorDist" -> 3.0,
        "turnAngle" -> 1.5,
        "decay" -> 0.1
      ),
      "Short, wide sensors and sharp turns: many separate blobs that do not join"
    )
  )
