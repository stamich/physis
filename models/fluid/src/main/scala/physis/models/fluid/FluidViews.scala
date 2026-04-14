package physis.models.fluid

import physis.view.{Channel, Colormap, Direction, Dots, ValueRange, View}

/** How Physis Lab draws the fluid. Plain data from physis-view; no toolkit. */
object FluidViews:
  /** A heatmap of a slice with the walls drawn over it in flat grey. */
  private def heatmap(
      slice: String,
      range: ValueRange,
      colormap: Colormap,
      title: String
  ): View.Heatmap =
    View.Heatmap(slice, range, colormap, title, overlay = Some("wall")) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  /** The dye, 0 to 1. */
  val dye: View.Heatmap = heatmap("dye", ValueRange.fixed(0.0, 1.0), Colormap.Viridis, "dye")

  /**
   * The vorticity with a diverging map centred at zero and a fixed scale, so that a swirl keeps
   * its colour from frame to frame: 0.5 per unit time is a swirl that turns once in 12 units.
   */
  val vorticity: View.Heatmap =
    heatmap("vorticity", ValueRange.symmetric(0.5), Colormap.Diverging, "vorticity")

  /** The speed, scaled to what is in the box now. */
  val speed: View.Heatmap = heatmap("speed", ValueRange.Auto, Colormap.Cividis, "speed")

  /** Channels of the probes in [[Fluid.probes]] order. */
  val energy: Channel = Channel.ProbeAt(0, "kinetic energy")
  val enstrophy: Channel = Channel.ProbeAt(2, "enstrophy")

  /** Kinetic energy over the last 300 time units. */
  val energyPlot: View = View.Plot(Seq(energy), window = 300.0, title = "Kinetic energy")

  /** Enstrophy over the last 300 time units. */
  val enstrophyPlot: View = View.Plot(Seq(enstrophy), window = 300.0, title = "Enstrophy")

  /** The two plots side by side. */
  val plots: View = View.Layout(Seq(energyPlot, enstrophyPlot), Direction.Horizontal)

  /** The tracer particles drawn as faint white dots over a heatmap. */
  def withTracers(base: View.Heatmap): View = View.Layers(base, Dots("x", "y", 0xffffffff, 110))

  /** The vorticity with the tracers over it. */
  val swirlWithTracers: View = withTracers(vorticity)

  /** The dye with the tracers over it. */
  val dyeWithTracers: View = withTracers(dye)

  /** The dye above, the plots below. */
  val default: View = View.Layout(Seq(dye, plots), Direction.Vertical)

  /** The vorticity above, the plots below. */
  val swirl: View = View.Layout(Seq(vorticity, plots), Direction.Vertical)
