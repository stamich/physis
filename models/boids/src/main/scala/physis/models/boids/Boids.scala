package physis.models.boids

import physis.agents.{NeighborGrid, ParticleSet}
import physis.core.{Model, Probe, StepContext, Stepping}
import physis.core.live.{Slice, StateExport}
import physis.core.param.{ParamSchema, ParamSpec, Preset}
import physis.core.random.SplittableRng
import physis.core.run.{Disc, Intervention}

/**
 * Parameters of the Boids model.
 *
 * @param perception     radius within which a boid sees its neighbours
 * @param separationDist neighbours closer than this push a boid away
 * @param wSeparation    weight of the separation steering
 * @param wAlignment     weight of the alignment steering
 * @param wCohesion      weight of the cohesion steering
 * @param maxSpeed       upper limit of a boid's speed, in world units per step
 * @param minSpeed       lower limit of a boid's speed
 * @param maxForce       largest change of velocity per step
 * @param wander         width of the random turn applied every step, in radians
 * @param n              number of boids (needs a restart)
 * @param width          world width (needs a restart)
 * @param height         world height (needs a restart)
 */
final case class BoidsParams(
    perception: Double,
    separationDist: Double,
    wSeparation: Double,
    wAlignment: Double,
    wCohesion: Double,
    maxSpeed: Double,
    minSpeed: Double,
    maxForce: Double,
    wander: Double,
    n: Int,
    width: Double,
    height: Double
)

object BoidsParams:
  given schema: ParamSchema[BoidsParams] = ParamSchema.derived[BoidsParams](
    ParamSpec("perception", 40.0, 5.0, 200.0, unit = "px", doc = "Neighbour radius"),
    ParamSpec("separationDist", 14.0, 1.0, 100.0, unit = "px", doc = "Personal space"),
    ParamSpec("wSeparation", 1.5, 0.0, 5.0, doc = "Weight of separation"),
    ParamSpec("wAlignment", 1.0, 0.0, 5.0, doc = "Weight of alignment"),
    ParamSpec("wCohesion", 0.8, 0.0, 5.0, doc = "Weight of cohesion"),
    ParamSpec("maxSpeed", 4.0, 0.5, 12.0, unit = "px/step", doc = "Speed limit"),
    ParamSpec("minSpeed", 1.5, 0.0, 12.0, unit = "px/step", doc = "Minimum speed"),
    ParamSpec("maxForce", 0.15, 0.01, 2.0, unit = "px/step", doc = "Largest velocity change"),
    ParamSpec("wander", 0.1, 0.0, 3.0, unit = "rad", doc = "Random turn per step"),
    ParamSpec("n", 1500, 2, 20000, doc = "Number of boids", restart = true),
    ParamSpec("width", 1200.0, 100.0, 5000.0, unit = "px", doc = "World width", restart = true),
    ParamSpec("height", 800.0, 100.0, 5000.0, unit = "px", doc = "World height", restart = true)
  )

/**
 * State of a Boids run: positions and velocities as a [[ParticleSet]], the buffers for the new
 * velocities, and the neighbour grid. A step allocates nothing unless `perception` changed,
 * which needs a new grid.
 */
final class BoidsState(
    val n: Int,
    val width: Double,
    val height: Double,
    val noiseKey: Long,
    perception: Double
):
  val particles: ParticleSet = ParticleSet(n, "x", "y", "vx", "vy")
  val x: Array[Double] = particles.column("x")
  val y: Array[Double] = particles.column("y")
  val vx: Array[Double] = particles.column("vx")
  val vy: Array[Double] = particles.column("vy")
  private val nextVx = new Array[Double](n)
  private val nextVy = new Array[Double](n)
  private var radius = perception
  private var grid = new NeighborGrid(n, width, height, perception)

  // Accumulators of the neighbour loop. They are fields, not locals of `step`: locals changed
  // inside the callback would become heap cells, one set per boid and step.
  private var accCount = 0
  private var accSepX = 0.0
  private var accSepY = 0.0
  private var accVelX = 0.0
  private var accVelY = 0.0
  private var accCohX = 0.0
  private var accCohY = 0.0

  /** See [[Boids.intervene]]. */
  def disturb(disc: Disc, strength: Double): Unit =
    val r2 = disc.r * disc.r
    val cosTurn = Noise.cos(strength)
    val sinTurn = Noise.sin(strength)
    var i = 0
    while i < n do
      var dx = x(i) - disc.x
      var dy = y(i) - disc.y
      dx -= width * math.rint(dx / width)
      dy -= height * math.rint(dy / height)
      val d2 = dx * dx + dy * dy
      if d2 <= r2 && d2 > 0.0 then
        val d = math.sqrt(d2)
        val rx = dx / d
        val ry = dy / d
        val speed = math.sqrt(vx(i) * vx(i) + vy(i) * vy(i))
        if speed > 0.0 then
          val cosAngle = (vx(i) * rx + vy(i) * ry) / speed
          if cosAngle >= cosTurn then
            vx(i) = speed * rx
            vy(i) = speed * ry
          else
            val side = if vx(i) * ry - vy(i) * rx < 0.0 then -1.0 else 1.0
            val ux = vx(i)
            val uy = vy(i)
            vx(i) = ux * cosTurn - side * uy * sinTurn
            vy(i) = side * ux * sinTurn + uy * cosTurn
      i += 1

  /** Neighbours counted in the latest step, summed over all boids. */
  var neighbourLinks: Long = 0L

  /** One step: new velocities from the old state, then every boid moves. See [[Boids]]. */
  def step(p: BoidsParams, stepIndex: Long): Unit =
    if p.perception != radius then
      radius = p.perception
      grid = new NeighborGrid(n, width, height, radius)
    val g = grid
    g.rebuild(x, y, n)
    val separation2 = p.separationDist * p.separationDist
    val invPerception = 1.0 / p.perception
    val invMaxSpeed = 1.0 / p.maxSpeed
    val maxSpeed2 = p.maxSpeed * p.maxSpeed
    val minSpeed2 = p.minSpeed * p.minSpeed
    var links = 0L
    var i = 0
    while i < n do
      accCount = 0
      accSepX = 0.0
      accSepY = 0.0
      accVelX = 0.0
      accVelY = 0.0
      accCohX = 0.0
      accCohY = 0.0
      g.foreachNear(i) { (j, dx, dy, d2) =>
        accCount += 1
        accVelX += vx(j)
        accVelY += vy(j)
        accCohX += dx
        accCohY += dy
        if d2 < separation2 && d2 > 0.0 then
          accSepX -= dx / d2
          accSepY -= dy / d2
      }
      val m = accCount
      val sepX = accSepX
      val sepY = accSepY
      val velX = accVelX
      val velY = accVelY
      val cohX = accCohX
      val cohY = accCohY
      links += m
      var steerX = 0.0
      var steerY = 0.0
      if m > 0 then
        val inv = 1.0 / m
        steerX = p.wSeparation * (sepX * p.separationDist) +
          p.wAlignment * ((velX * inv - vx(i)) * invMaxSpeed) +
          p.wCohesion * ((cohX * inv) * invPerception)
        steerY = p.wSeparation * (sepY * p.separationDist) +
          p.wAlignment * ((velY * inv - vy(i)) * invMaxSpeed) +
          p.wCohesion * ((cohY * inv) * invPerception)
        // The steering is dimensionless; a length of 1 means "change velocity by maxForce".
        val s2 = steerX * steerX + steerY * steerY
        if s2 > 1.0 then
          val scale = 1.0 / math.sqrt(s2)
          steerX *= scale
          steerY *= scale
      var newX = vx(i) + p.maxForce * steerX
      var newY = vy(i) + p.maxForce * steerY
      if p.wander > 0.0 then
        val angle = (Noise.uniform(noiseKey, stepIndex, i) - 0.5) * p.wander
        val c = Noise.cos(angle)
        val s = Noise.sin(angle)
        val rx = c * newX - s * newY
        newY = s * newX + c * newY
        newX = rx
      val speed2 = newX * newX + newY * newY
      if speed2 > maxSpeed2 then
        val scale = p.maxSpeed / math.sqrt(speed2)
        newX *= scale
        newY *= scale
      else if speed2 < minSpeed2 then
        if speed2 == 0.0 then newX = p.minSpeed
        else
          val scale = p.minSpeed / math.sqrt(speed2)
          newX *= scale
          newY *= scale
      nextVx(i) = newX
      nextVy(i) = newY
      i += 1
    neighbourLinks = links
    i = 0
    while i < n do
      vx(i) = nextVx(i)
      vy(i) = nextVy(i)
      x(i) = wrap(x(i) + vx(i), width)
      y(i) = wrap(y(i) + vy(i), height)
      i += 1

  /** Position `v` brought back into `[0, size)`; a boid moves less than half the world. */
  private def wrap(v: Double, size: Double): Double =
    if v >= size then v - size
    else if v < 0.0 then
      val w = v + size
      if w >= size then 0.0 else w
    else v

  /** Polarization |sum v| / sum |v|: 0 for random headings, 1 for a perfectly aligned flock. */
  def polarization: Double =
    var sx = 0.0
    var sy = 0.0
    var speeds = 0.0
    var i = 0
    while i < n do
      sx += vx(i)
      sy += vy(i)
      speeds += math.sqrt(vx(i) * vx(i) + vy(i) * vy(i))
      i += 1
    if speeds == 0.0 then 0.0 else math.sqrt(sx * sx + sy * sy) / speeds

  /** Mean speed. */
  def meanSpeed: Double =
    var total = 0.0
    var i = 0
    while i < n do
      total += math.sqrt(vx(i) * vx(i) + vy(i) * vy(i))
      i += 1
    total / n

object BoidsState:
  /** Live runs export x, y, vx, vy as four rows of n values. */
  given stateExport: StateExport[BoidsState] with
    def size(state: BoidsState): Int = state.particles.size
    def copyInto(state: BoidsState, out: Array[Double]): Unit = state.particles.copyInto(out, 0)
    override def layout(state: BoidsState): IndexedSeq[Slice] = state.particles.slices(0)

/**
 * Random numbers from a counter instead of a stream: a pure function of (key, step, boid), and
 * the sine and cosine of small angles, from the Taylor series in plain arithmetic.
 */
object Noise:
  /**
   * `sin(a)` for `|a| <= 2` (error below 1e-15). Plain additions, multiplications and divisions,
   * so the result is the same on every platform, and unlike `StrictMath.sin` it allocates nothing.
   */
  def sin(a: Double): Double =
    val a2 = a * a
    a * (1.0 - a2 / 6.0 * (1.0 - a2 / 20.0 * (1.0 - a2 / 42.0 * (1.0 - a2 / 72.0 *
      (1.0 - a2 / 110.0 * (1.0 - a2 / 156.0 * (1.0 - a2 / 210.0 * (1.0 - a2 / 272.0 *
        (1.0 - a2 / 342.0 * (1.0 - a2 / 420.0))))))))))

  /** `cos(a)` for `|a| <= 2` (error below 1e-15); see [[sin]]. */
  def cos(a: Double): Double =
    val a2 = a * a
    1.0 - a2 / 2.0 * (1.0 - a2 / 12.0 * (1.0 - a2 / 30.0 * (1.0 - a2 / 56.0 *
      (1.0 - a2 / 90.0 * (1.0 - a2 / 132.0 * (1.0 - a2 / 182.0 * (1.0 - a2 / 240.0 *
        (1.0 - a2 / 306.0 * (1.0 - a2 / 380.0)))))))))

  private def mix(z0: Long): Long =
    var z = z0 + 0x9e3779b97f4a7c15L
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

  /** Uniform in [0, 1), the same on every platform: integer arithmetic only. */
  def uniform(key: Long, step: Long, index: Int): Double =
    val bits = mix(key ^ mix(step * 0x632be59bd9b4e019L + index))
    (bits >>> 11).toDouble * (1.0 / 9007199254740992.0)

/**
 * Boids (Reynolds 1986) on a periodic 2D world, with a random turn that makes the alignment-only
 * case the Vicsek model.
 *
 * Every step, each boid looks at the neighbours within `perception` (minimum-image distance,
 * found with a [[NeighborGrid]]) and forms three dimensionless steering terms:
 *
 *   - separation: `sum of -d / |d|^2` over neighbours closer than `separationDist`, times
 *     `separationDist` (so a neighbour at that distance counts 1, a closer one more);
 *   - alignment: the mean neighbour velocity minus its own, over `maxSpeed`;
 *   - cohesion: the mean displacement to the neighbours, over `perception`.
 *
 * The weighted sum is limited to length 1 and scaled by `maxForce`, which is the largest change
 * of velocity per step. The velocity is then rotated by a uniform random angle in
 * `[-wander / 2, wander / 2]`, its speed is clamped to `[minSpeed, maxSpeed]`, and the position
 * advances by one velocity and wraps. All new velocities come from the old state, so the result
 * does not depend on the order of the boids (up to the rounding of sums over neighbours, which
 * the fixed neighbour order makes reproducible for a given order).
 *
 * One step is 1/60 time unit ([[stepping]]); motion is in world units per step. The random turn
 * is a pure function of a key drawn at start, the step and the boid index, so steps allocate
 * nothing and results are bit-identical on every platform (a plain-arithmetic series for the rotation: `StrictMath` allocates in its trigonometry).
 */
object Boids extends Model[BoidsParams]:
  type State = BoidsState
  val id = "boids"
  val version = "0.3.0"
  val params: ParamSchema[BoidsParams] = BoidsParams.schema

  /** One step is 1/60 time unit, so speed 1x is 60 steps per second. */
  val stepping: Stepping = Stepping.Fixed(1.0 / 60.0)

  override def check(p: BoidsParams, stepping: Stepping): Either[String, Unit] =
    if p.minSpeed > p.maxSpeed then
      Left(s"minSpeed (${p.minSpeed}) must not exceed maxSpeed (${p.maxSpeed})")
    else Right(())

  def init(p: BoidsParams, rng: SplittableRng): BoidsState =
    val state = new BoidsState(p.n, p.width, p.height, rng.nextLong(), p.perception)
    var i = 0
    while i < p.n do
      state.x(i) = rng.nextDouble() * p.width
      state.y(i) = rng.nextDouble() * p.height
      val heading = 2.0 * math.Pi * rng.nextDouble()
      val speed = p.minSpeed + (p.maxSpeed - p.minSpeed) * rng.nextDouble()
      state.vx(i) = speed * StrictMath.cos(heading)
      state.vy(i) = speed * StrictMath.sin(heading)
      i += 1
    state

  def advance(state: BoidsState, p: BoidsParams, dt: Double, ctx: StepContext): Unit =
    state.step(p, ctx.step)
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: BoidsState): Long = state.particles.digest

  /** Largest turn of a Disturb, in radians: the range of the rotation series in [[Noise]]. */
  val MaxDisturb: Double = 2.0

  override def accepts(iv: Intervention): Either[String, Unit] = iv match
    case Intervention.Disturb(_, strength) =>
      if strength > 0.0 && strength <= MaxDisturb then Right(())
      else Left(s"disturb strength must be in (0, $MaxDisturb] radians, got $strength")
    case _ => Left("boids only take Disturb: they have no fields to paint")

  /**
   * Disturb turns every boid whose position is inside the disc (minimum-image distance on the
   * periodic world, in world units) towards the direction pointing away from the disc centre by
   * `strength` radians, or exactly onto it when it is closer than that. The speed is kept. It
   * uses no random numbers and no library trigonometry, so it replays exactly on every platform.
   */
  override def intervene(
      state: BoidsState,
      p: BoidsParams,
      iv: Intervention
  ): Either[String, Unit] =
    accepts(iv).map { _ =>
      iv match
        case Intervention.Disturb(disc, strength) => state.disturb(disc, strength)
        case _ => ()
    }

  /** Order parameter of the flock: 0 for random headings, 1 for a perfectly aligned flock. */
  val polarization: Probe[BoidsState] = Probe[BoidsState]("polarization")((s, _) => s.polarization)

  /** Mean speed in world units per step. */
  val meanSpeed: Probe[BoidsState] = Probe[BoidsState]("mean_speed")((s, _) => s.meanSpeed)

  /** Mean number of neighbours per boid, as counted in the latest step. */
  val meanNeighbours: Probe[BoidsState] =
    Probe[BoidsState]("mean_neighbours")((s, _) => s.neighbourLinks.toDouble / s.n)

  val probes: Vector[Probe[BoidsState]] = Vector(polarization, meanSpeed, meanNeighbours)

  val presets: Vector[Preset] = Vector(
    Preset(
      "Flock",
      Map(
        "wSeparation" -> 1.5,
        "wAlignment" -> 1.0,
        "wCohesion" -> 0.8,
        "wander" -> 0.1
      ),
      "Balanced rules: a loose, coherent flock"
    ),
    Preset(
      "Swarm",
      Map(
        "wSeparation" -> 1.0,
        "wAlignment" -> 0.2,
        "wCohesion" -> 2.0,
        "wander" -> 0.3
      ),
      "Strong cohesion, little alignment: a milling swarm"
    ),
    Preset(
      "Vicsek (disordered)",
      Map(
        "wSeparation" -> 0.0,
        "wAlignment" -> 1.0,
        "wCohesion" -> 0.0,
        "wander" -> 2.5
      ),
      "Alignment only with strong noise: no collective motion"
    )
  )
