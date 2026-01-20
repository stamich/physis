package physis.core.run

import scala.collection.immutable.VectorMap
import scala.collection.mutable.ArrayBuffer

import physis.core.{Model, Probe, StepContext, Stepping}
import physis.core.random.SplittableRng

/**
 * One simulation run: a model, its parameters, a stepping policy and a seed.
 *
 * The run owns the state, the clock and the step loop. Create one with [[Physis.model]].
 *
 * Sampling lands exactly on sample times: when the next step would overshoot, the run shortens
 * it. Results are therefore independent of how step sizes relate to the sampling interval.
 *
 * With [[Stepping.Adaptive]] the run passes the tolerances to the model through the
 * [[StepContext]] and follows the model's suggested next step, capped at `maxDt`. Steps
 * shortened only to land on a sample time do not reset that proposal.
 */
final class Run[P, S] private[run] (
    val model: Model.Aux[P, S],
    val params: P,
    val stepping: Stepping,
    val seed: Long,
    val probes: Vector[Probe[S]],
    val sampleEvery: Double
):
  private val rootRng = SplittableRng(seed)
  private val ctx = StepContext()
  ctx.seedWith(rootRng.fork(Run.StepStreamsKey))

  /** The simulated state. Owned by the run: read it, do not modify it. */
  val state: S = model.init(params, rootRng.fork(Run.InitStreamKey))

  private var t = 0.0
  private var n = 0L
  private var rejectedTotal = 0L
  private val adaptive: Option[Stepping.Adaptive] = stepping match
    case a: Stepping.Adaptive => Some(a)
    case _: Stepping.Fixed => None

  /** Step size proposed for the next step. */
  private var nextDt: Double = stepping match
    case Stepping.Fixed(dt) => dt
    case a: Stepping.Adaptive => a.initialDt

  adaptive.foreach { a =>
    ctx.adaptive = true
    ctx.rtol = a.rtol
    ctx.atol = a.atol
  }

  /** Current simulation time. */
  def time: Double = t

  /** Accepted steps so far. */
  def stepCount: Long = n

  /** Rejected attempts so far (adaptive stepping). */
  def rejectedCount: Long = rejectedTotal

  /** Advances to exactly `target`, shortening the last step if needed. */
  def advanceTo(target: Double): Unit =
    require(!(target < t), s"cannot advance backwards from $t to $target")
    while t < target do
      val remaining = target - t
      if remaining <= Run.LandingTolerance * math.max(math.abs(target), nextDt) then t = target
      else
        val shortened = remaining < nextDt
        val dt = if shortened then remaining else nextDt
        stepOnce(dt)
        t = if shortened && ctx.taken == dt then target else t + ctx.taken
        adaptive match
          case Some(a) =>
            // A step shortened only to land on a sample time keeps the previous proposal;
            // otherwise follow the model's suggestion, capped at maxDt.
            if (!shortened || ctx.rejected > 0) && ctx.suggestedNext > 0.0 then
              nextDt = math.min(ctx.suggestedNext, a.maxDt)
          case None => ()

  /** Runs to `until`, sampling every probe at the start and every `sampleEvery`. */
  def runUntil(until: Double): RunResult =
    require(!(until < t), s"cannot run backwards from $t to $until")
    val times = ArrayBuffer.empty[Double]
    val columns = Vector.fill(probes.size)(ArrayBuffer.empty[Double])

    def sample(): Unit =
      times += t
      var i = 0
      while i < probes.size do
        columns(i) += probes(i).measure(state, t)
        i += 1

    val start = t
    sample()
    var k = 1L
    var next = start + k * sampleEvery
    while next < until - Run.LandingTolerance * math.max(math.abs(until), sampleEvery) do
      advanceTo(next)
      sample()
      k += 1
      next = start + k * sampleEvery
    advanceTo(until)
    if times.last != t then sample()

    RunResult(
      times = times.toVector,
      series = VectorMap.from(probes.map(_.name).zip(columns.map(_.toVector))),
      acceptedSteps = n,
      rejectedSteps = rejectedTotal,
      finalTime = t,
      finalDigest = model.digest(state)
    )

  /** Fingerprint of the current state. */
  def digest: Long = model.digest(state)

  private def stepOnce(dt: Double): Unit =
    ctx.step = n
    ctx.time = t
    ctx.taken = 0.0
    ctx.suggestedNext = dt
    ctx.rejected = 0
    model.advance(state, params, dt, ctx)
    val taken = ctx.taken
    if !(taken > 0.0 && taken <= dt) then
      throw new IllegalStateException(
        s"model '${model.id}' reported a step of $taken for a requested $dt at t = $t"
      )
    n += 1
    rejectedTotal += ctx.rejected

object Run:
  /** Relative distance below which a remaining interval counts as already reached. */
  private val LandingTolerance = 1e-12

  private val InitStreamKey = -1L
  private val StepStreamsKey = -2L

/**
 * Probe series collected by [[Run.runUntil]].
 *
 * @param times         sample times, starting with the time the run was resumed from
 * @param series        one column per probe, aligned with `times`
 * @param acceptedSteps accepted steps since the run was created
 * @param rejectedSteps rejected attempts since the run was created
 * @param finalTime     time at the end of the call
 * @param finalDigest   bit-exact fingerprint of the final state
 */
final case class RunResult(
    times: Vector[Double],
    series: VectorMap[String, Vector[Double]],
    acceptedSteps: Long,
    rejectedSteps: Long,
    finalTime: Double,
    finalDigest: Long
):
  def column(name: String): Vector[Double] =
    series.getOrElse(name, throw new NoSuchElementException(s"no probe named '$name'"))

  /** Last sampled value of a probe. */
  def last(name: String): Double = column(name).last

/** Entry point for building runs. */
object Physis:
  /** Starts a run definition for `model` with default parameters. */
  def model[P](model: Model[P]): RunBuilder[P, model.State] = RunBuilder.of(model)

/** Immutable run definition; `build()` validates it and creates the [[Run]]. */
final class RunBuilder[P, S] private (cfg: RunBuilder.Config[P, S]):
  def params(f: P => P): RunBuilder[P, S] = withParams(f(cfg.params))

  def withParams(p: P): RunBuilder[P, S] = new RunBuilder(cfg.copy(params = p))

  /** Replaces named parameter values; fails on unknown names or out-of-range values. */
  def overrides(values: Map[String, Double]): RunBuilder[P, S] =
    cfg.model.params.withOverrides(cfg.params, values) match
      case Right(p) => withParams(p)
      case Left(error) => throw new IllegalArgumentException(error)

  def stepping(s: Stepping): RunBuilder[P, S] = new RunBuilder(cfg.copy(stepping = s))

  def seed(s: Long): RunBuilder[P, S] = new RunBuilder(cfg.copy(seed = s))

  def probe(ps: Probe[S]*): RunBuilder[P, S] = new RunBuilder(cfg.copy(probes = cfg.probes ++ ps))

  def sampleEvery(dt: Double): RunBuilder[P, S] = new RunBuilder(cfg.copy(sampleEvery = dt))

  def build(): Run[P, S] =
    cfg.stepping.validate.foreach(error => throw new IllegalArgumentException(error))
    if !cfg.model.supports(cfg.stepping) then
      val kind = cfg.stepping match
        case _: Stepping.Fixed => "fixed"
        case _: Stepping.Adaptive => "adaptive"
      throw new IllegalArgumentException(
        s"model '${cfg.model.id}' does not support $kind stepping"
      )
    require(cfg.sampleEvery > 0.0 && !cfg.sampleEvery.isInfinite, "sampleEvery must be positive")
    val names = cfg.probes.map(_.name)
    require(names.distinct.size == names.size, s"duplicate probe names: ${names.mkString(", ")}")
    new Run(cfg.model, cfg.params, cfg.stepping, cfg.seed, cfg.probes, cfg.sampleEvery)

object RunBuilder:
  private[run] final case class Config[P, S](
      model: Model.Aux[P, S],
      params: P,
      stepping: Stepping,
      seed: Long,
      probes: Vector[Probe[S]],
      sampleEvery: Double
  )

  private[run] def of[P](model: Model[P]): RunBuilder[P, model.State] =
    new RunBuilder(
      Config[P, model.State](
        model = model,
        params = model.params.defaults,
        stepping = Stepping.Fixed(0.01),
        seed = 0L,
        probes = Vector.empty,
        sampleEvery = 0.1
      )
    )
