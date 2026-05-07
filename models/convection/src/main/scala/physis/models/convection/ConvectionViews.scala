package physis.models.convection

import physis.view.{Channel, Colormap, Direction, ValueRange, View}

/** How Physis Lab draws the convection. Plain data from physis-view; no toolkit. */
object ConvectionViews:
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

  /** The temperature, 0 at the lid to 1 at the floor: hot is bright. */
  val temperature: View.Heatmap =
    heatmap("temperature", ValueRange.fixed(0.0, 1.0), Colormap.Viridis, "temperature")

  /**
   * The deviation from the conduction profile, on a diverging map centred at zero with a fixed
   * scale: warm fluid that rises is red, cold fluid that sinks is blue.
   */
  val deviation: View.Heatmap =
    heatmap("deviation", ValueRange.symmetric(0.3), Colormap.Diverging, "deviation from conduction")

  /** The vorticity, diverging and centred at zero. */
  val vorticity: View.Heatmap =
    heatmap("vorticity", ValueRange.symmetric(0.1), Colormap.Diverging, "vorticity")

  /** The speed, scaled to what is in the box now. */
  val speed: View.Heatmap = heatmap("speed", ValueRange.Auto, Colormap.Cividis, "speed")

  /** Channels of the probes in [[Convection.probes]] order. */
  val energy: Channel = Channel.ProbeAt(0, "kinetic energy")
  val nusselt: Channel = Channel.ProbeAt(2, "Nusselt number")

  /** Kinetic energy over the last 3 000 time units (a few times the diffusion time). */
  val energyPlot: View = View.Plot(Seq(energy), window = 3000.0, title = "Kinetic energy")

  /** Nusselt number over the last 3 000 time units. */
  val nusseltPlot: View = View.Plot(Seq(nusselt), window = 3000.0, title = "Nusselt number")

  /** The two plots side by side. */
  val plots: View = View.Layout(Seq(energyPlot, nusseltPlot), Direction.Horizontal)

  /** The temperature above, the plots below. */
  val default: View = View.Layout(Seq(temperature, plots), Direction.Vertical)

  /** What the Lab shows. */
  val lab: View = default

  /** The deviation above, the plots below. */
  val rising: View = View.Layout(Seq(deviation, plots), Direction.Vertical)
