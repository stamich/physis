package physis.view

import physis.core.live.Snapshot

/** Reads one number from a snapshot: a component of the state vector or a probe value. */
enum Channel:
  /** Component `index` of the exported state vector. */
  case StateAt(index: Int, label: String)

  /** Value of probe `index`, in the order the probes were added to the run. */
  case ProbeAt(index: Int, label: String)

  /** Human-readable name, used for axis labels and legends. */
  def label: String

  /** The channel's value in `snapshot`; NaN if the index is out of range. */
  def read(snapshot: Snapshot): Double = this match
    case StateAt(i, _) =>
      if i >= 0 && i < snapshot.state.length then snapshot.state(i) else Double.NaN
    case ProbeAt(i, _) =>
      if i >= 0 && i < snapshot.probes.length then snapshot.probes(i) else Double.NaN
