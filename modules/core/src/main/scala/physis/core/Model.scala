package physis.core

import physis.core.param.ParamSchema
import physis.core.random.SplittableRng

/**
 * A simulation model: how a state is created and how it advances in time.
 *
 * Ownership rules, which every family of models (ODE, grid, agents) follows:
 *
 *   - The engine (a [[physis.core.run.Run]]) owns time, the step loop and the state instance.
 *     The model never loops on its own.
 *   - `advance` mutates the state in place and must not allocate per step in steady state.
 *     Scratch buffers belong to the model instance, so one instance must not be advanced from
 *     two threads at the same time.
 *   - All randomness comes from `StepContext.rng` (or the `rng` given to `init`), never from
 *     global sources or the clock, so runs are reproducible from their seed.
 *
 * @tparam P parameter type, described by [[params]]
 */
trait Model[P]:
  /** Mutable state advanced by [[advance]]. */
  type State

  /** Stable identifier, e.g. "lorenz". Recorded in run manifests. */
  def id: String

  /** Model version, recorded in run manifests; bump it when results change. */
  def version: String

  def params: ParamSchema[P]

  /** Whether runs may use this stepping policy. Fixed steps only, unless overridden. */
  def supports(stepping: Stepping): Boolean = stepping match
    case _: Stepping.Fixed => true
    case _: Stepping.Adaptive => false

  /** Creates the initial state. */
  def init(p: P, rng: SplittableRng): State

  /**
   * Advances `state` in place by at most `dt`, starting at `ctx.time`.
   *
   * Implementations must set `ctx.taken` (0 < taken <= dt) and `ctx.suggestedNext`, and add
   * rejected attempts to `ctx.rejected`. Fixed-step models take exactly `dt`.
   */
  def advance(state: State, p: P, dt: Double, ctx: StepContext): Unit

  /** Bit-exact fingerprint of the state, used to check reproducibility. */
  def digest(state: State): Long

object Model:
  /** A model whose state type is known. */
  type Aux[P, S] = Model[P] { type State = S }

/**
 * Per-step information exchanged between the engine and a model. One instance per run, reused
 * for every step; never allocated per step.
 */
final class StepContext:
  /** Index of the step being taken, starting at 0. */
  var step: Long = 0L

  /** Simulation time at the start of the step. */
  var time: Double = 0.0

  /** True when the run uses [[Stepping.Adaptive]]. */
  var adaptive: Boolean = false

  /** Relative tolerance for adaptive steps. */
  var rtol: Double = 0.0

  /** Absolute tolerance for adaptive steps. */
  var atol: Double = 0.0

  /** Set by the model: the step actually taken. */
  var taken: Double = 0.0

  /** Set by the model: the step it would like to take next. */
  var suggestedNext: Double = 0.0

  /** Set by the model: attempts rejected while taking this step. */
  var rejected: Int = 0

  private var root: SplittableRng = SplittableRng(0L)

  /** Random stream for this step: a pure function of the run seed and the step index. */
  def rng: SplittableRng = root.fork(step)

  private[physis] def seedWith(rootRng: SplittableRng): Unit =
    root = rootRng

/** How the engine chooses step sizes. */
enum Stepping:
  /** Every step has the same size `dt`, except a final step shortened to land on a sample time. */
  case Fixed(dt: Double)

  /** Step size is controlled by the model's error estimate. */
  case Adaptive(rtol: Double, atol: Double, initialDt: Double, maxDt: Double)

  /** Describes what is wrong with this policy, if anything. */
  def validate: Option[String] = this match
    case Fixed(dt) =>
      if dt > 0.0 && !dt.isInfinite then None else Some(s"Fixed: dt must be positive, got $dt")
    case Adaptive(rtol, atol, initialDt, maxDt) =>
      if !(rtol > 0.0) then Some(s"Adaptive: rtol must be positive, got $rtol")
      else if !(atol >= 0.0) then Some(s"Adaptive: atol must be non-negative, got $atol")
      else if !(initialDt > 0.0) then Some(s"Adaptive: initialDt must be positive, got $initialDt")
      else if !(maxDt >= initialDt) then Some(s"Adaptive: maxDt ($maxDt) < initialDt ($initialDt)")
      else None

/**
 * A scalar measurement of the state, recorded at sample times.
 *
 * @tparam S state type it can measure
 */
trait Probe[-S]:
  def name: String
  def unit: String
  def measure(state: S, time: Double): Double

object Probe:
  /** A probe from a function of state and time. */
  def apply[S](name: String, unit: String = "1")(f: (S, Double) => Double): Probe[S] =
    val probeName = name
    val probeUnit = unit
    new Probe[S]:
      def name: String = probeName
      def unit: String = probeUnit
      def measure(state: S, time: Double): Double = f(state, time)

/** Bit-exact fingerprints of numeric data (FNV-1a over the raw IEEE-754 bits). */
object Digest:
  private val Offset = 0xcbf29ce484222325L
  private val Prime = 0x100000001b3L

  def ofDoubles(values: Array[Double]): Long =
    var h = Offset
    var i = 0
    while i < values.length do
      h = mixLong(h, java.lang.Double.doubleToRawLongBits(values(i)))
      i += 1
    h

  def ofDoubles(values: Double*): Long = ofDoubles(values.toArray)

  private def mixLong(h0: Long, v: Long): Long =
    var h = h0
    var shift = 0
    while shift < 64 do
      h = (h ^ ((v >>> shift) & 0xffL)) * Prime
      shift += 8
    h
