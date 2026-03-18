package physis.models.physarum

import physis.view.{Channel, Colormap, Direction, Dots, ValueRange, View}

/** How Physis Lab draws Physarum. Plain data from physis-view; no toolkit. */
object PhysarumViews:
  /** The trail, scaled to what is on the grid now. */
  val trail: View.Heatmap = View.Heatmap("trail", ValueRange.Auto, Colormap.Viridis, "trail") match
    case h: View.Heatmap => h
    case other => throw new IllegalStateException(s"not a heatmap: $other")

  /** The trail with the agents drawn over it as faint white dots. */
  val withAgents: View = View.Layers(trail, Dots("x", "y", 0xffffffff, 64))

  /** Channels of the probes in [[Physarum.probes]] order: trail_mass, coverage, structures. */
  val mass: Channel = Channel.ProbeAt(0, "trail mass")
  val structures: Channel = Channel.ProbeAt(2, "structures")

  /** Total trail over the last 600 steps (one time unit is one step). */
  val massPlot: View = View.Plot(Seq(mass), window = 600.0, title = "Trail mass")

  /** Number of connected structures over the last 600 steps. */
  val structuresPlot: View = View.Plot(Seq(structures), window = 600.0, title = "Structures")

  /** The two plots side by side. */
  val plots: View = View.Layout(Seq(massPlot, structuresPlot), Direction.Horizontal)

  /** The trail above, the plots below. */
  val default: View = View.Layout(Seq(withAgents, plots), Direction.Vertical)
