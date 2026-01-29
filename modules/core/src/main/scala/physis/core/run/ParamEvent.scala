package physis.core.run

import scala.collection.immutable.VectorMap

/**
 * A parameter change applied immediately before step `step` (steps count from 0).
 *
 * Runs record every change made through [[Run.setParams]] as an event, and replay applies the
 * same events before the same steps, so a run whose parameters were changed while it ran is as
 * reproducible as one that was not.
 *
 * @param step   index of the first step that uses the new values
 * @param values changed parameters only, in schema order
 */
final case class ParamEvent(step: Long, values: VectorMap[String, Double])
