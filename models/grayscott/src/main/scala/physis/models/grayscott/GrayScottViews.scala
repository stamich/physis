package physis.models.grayscott

import physis.view.{Channel, Colormap, Direction, ValueRange, View}

/** How Physis Lab draws Gray-Scott. Plain data from physis-view; no toolkit. */
object GrayScottViews:
  /** u, between 0 and 1. */
  val u: View = View.Heatmap("u", ValueRange.fixed(0.0, 1.0), Colormap.Cividis, "u")

  /** v, between 0 and 1: the pattern. */
  val v: View = View.Heatmap("v", ValueRange.fixed(0.0, 1.0), Colormap.Viridis, "v")

  /** Both fields side by side. */
  val fields: View = View.Layout(Seq(u, v), Direction.Horizontal)

  /** Channels of the probes in [[GrayScott.probes]] order: u_mean, v_mean, v_max, coverage. */
  val vMean: Channel = Channel.ProbeAt(1, "mean v")
  val vMax: Channel = Channel.ProbeAt(2, "max v")
  val coverage: Channel = Channel.ProbeAt(3, "coverage")

  /** Mean v, max v and coverage over the last 5 000 steps (one time unit is one step). */
  val statistics: View = View.Plot(Seq(vMean, vMax, coverage), window = 5000.0, title = "Pattern")

  /** The two fields above, the statistics below. */
  val default: View = View.Layout(Seq(fields, statistics), Direction.Vertical)
