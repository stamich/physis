package physis.core.live

/**
 * What the UI may read about a live run, copied out by the simulation thread.
 *
 * Snapshots are preallocated (three per live run, see [[TripleBuffer]]) and refilled in place,
 * so publishing allocates nothing in steady state. A snapshot is only ever read by one thread
 * at a time: the simulation thread fills it while it is the back buffer, the UI reads it once
 * it has been handed over.
 *
 * @param stateSize  length of the exported state vector
 * @param probeCount number of probes of the run
 */
final class Snapshot(val stateSize: Int, val probeCount: Int):
  /** Simulation time. */
  var time: Double = 0.0

  /** Whole steps taken so far. */
  var step: Long = 0L

  /** Rejected attempts so far (adaptive stepping). */
  var rejected: Long = 0L

  /** Measured simulation speed, in steps per wall-clock second. */
  var stepsPerSecond: Double = 0.0

  /** Requested simulation speed, in simulated seconds per wall-clock second. */
  var speed: Double = 1.0

  /** Whether the live run is paused. */
  var paused: Boolean = false

  /** `System.nanoTime` when the snapshot was published. */
  var publishedAt: Long = 0L

  /** Last error reported to the UI (for example an invalid parameter value), or empty. */
  var error: String = ""

  /** The exported state vector. */
  val state: Array[Double] = new Array[Double](stateSize)

  /** Probe values, in the order of the run's probes. */
  val probes: Array[Double] = new Array[Double](probeCount)

  /** Parameter names in schema order; set once per run. */
  var paramNames: IndexedSeq[String] = Vector.empty

  /** Current parameter values in schema order; replaced only when parameters change. */
  var paramValues: IndexedSeq[Double] = Vector.empty

  /** Value of parameter `name`, or NaN if unknown. */
  def param(name: String): Double =
    val i = paramNames.indexOf(name)
    if i < 0 then Double.NaN else paramValues(i)

/**
 * Copies a model state into a flat array without allocating, so the UI can read it.
 *
 * @tparam S state type
 */
trait StateExport[-S]:
  /** Length of the exported vector for `state`. Constant for the lifetime of a run. */
  def size(state: S): Int

  /** Writes the exported vector into `out`, which has at least `size(state)` elements. */
  def copyInto(state: S, out: Array[Double]): Unit
