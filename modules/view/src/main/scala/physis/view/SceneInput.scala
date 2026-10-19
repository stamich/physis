package physis.view

import physis.core.live.Snapshot

/**
 * What a [[View.Scene2D]] drawing function may read: time, state, probes and parameters by
 * name. A view over the current snapshot, valid only during the call it is passed to.
 */
trait SceneInput:
  /** Simulated time. */
  def time: Double

  /** Component `i` of the exported state vector. */
  def state(i: Int): Double

  /** Value of probe `i`. */
  def probe(i: Int): Double

  /** Current value of parameter `name`; NaN if the model has no such parameter. */
  def param(name: String): Double

object SceneInput:
  /** Reads `snapshot` directly, without copying. */
  def of(snapshot: Snapshot): SceneInput = new SceneInput:
    def time: Double = snapshot.time
    def state(i: Int): Double = snapshot.state(i)
    def probe(i: Int): Double = snapshot.probes(i)
    def param(name: String): Double = snapshot.param(name)

  /** Input from plain values, for tests and offline rendering. */
  def of(
      at: Double,
      stateValues: IndexedSeq[Double],
      probeValues: IndexedSeq[Double] = Vector.empty,
      params: Map[String, Double] = Map.empty
  ): SceneInput =
    new SceneInput:
      def time: Double = at
      def state(i: Int): Double = stateValues(i)
      def probe(i: Int): Double = probeValues(i)
      def param(name: String): Double = params.getOrElse(name, Double.NaN)
