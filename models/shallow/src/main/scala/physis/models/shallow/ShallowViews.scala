package physis.models.shallow

import physis.view.{Channel, Colormap, Direction, ValueRange, View}

/** How Physis Lab draws the sea. Plain data from physis-view; no toolkit. North is at the top. */
object ShallowViews:
  /** A heatmap of a slice with the land drawn over it in flat grey. */
  private def heatmap(
      slice: String,
      range: ValueRange,
      colormap: Colormap,
      title: String
  ): View.Heatmap =
    View.Heatmap(slice, range, colormap, title, overlay = Some("land")) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  /** The height of the surface, diverging and centred at zero on a scale that follows the waves. */
  val height: View.Heatmap =
    heatmap("height", ValueRange.SymmetricAuto, Colormap.Diverging, "surface height (m)")

  /** The speed of the water, scaled to what is in the sea now. */
  val speed: View.Heatmap = heatmap("speed", ValueRange.Auto, Colormap.Cividis, "speed (m/s)")

  /** The relative vorticity, positive counter-clockwise, centred at zero. */
  val vorticity: View.Heatmap =
    heatmap("vorticity", ValueRange.SymmetricAuto, Colormap.Diverging, "vorticity (1/s)")

  /** The depth of the water at rest. */
  val depth: View.Heatmap = heatmap("depth", ValueRange.Auto, Colormap.Viridis, "depth (m)")

  /**
   * The depth of the water now. Where the shore is dry the cell is drawn in the grey of the
   * land, so the edge of the grey is the shoreline.
   */
  val water: View.Heatmap =
    View.Heatmap(
      "water",
      ValueRange.Auto,
      Colormap.Viridis,
      "water depth (m)",
      overlay = Some("dry")
    ) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  /**
   * The map of the globe: the height on the longitude-latitude grid, west to east and north at
   * the top, with the meridians and parallels every 30° drawn over it.
   */
  val map: View.Heatmap =
    View.Heatmap(
      "height",
      ValueRange.SymmetricAuto,
      Colormap.Diverging,
      "surface height (m)",
      overlay = Some("graticule")
    ) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  /** When the wave first reached each place, in s (-1 where it has not yet). */
  val arrivalMap: View.Heatmap =
    View.Heatmap(
      "arrival",
      ValueRange.Auto,
      Colormap.Viridis,
      "arrival time (s)",
      overlay = Some("graticule")
    ) match
      case h: View.Heatmap => h
      case other => throw new IllegalStateException(s"not a heatmap: $other")

  /** Channels of the probes in [[Shallow.probes]] order. */
  val energy: Channel = Channel.ProbeAt(1, "energy")
  val highest: Channel = Channel.ProbeAt(2, "highest surface")

  /** Energy over the last 200 000 s (55 hours of the sea). */
  val energyPlot: View = View.Plot(Seq(energy), window = 200_000.0, title = "Energy")

  /** The highest the surface stands, over the same time. */
  val highestPlot: View =
    View.Plot(Seq(highest), window = 200_000.0, title = "Highest surface (m)")

  /** The two plots side by side. */
  val plots: View = View.Layout(Seq(energyPlot, highestPlot), Direction.Horizontal)

  /** The surface above, the plots below. */
  val default: View = View.Layout(Seq(height, plots), Direction.Vertical)

  /** What the Lab shows. */
  val lab: View = default

  /** The depth of the water above, the plots below: for a coast that floods. */
  val flooding: View = View.Layout(Seq(water, plots), Direction.Vertical)

  /** The speed above, the plots below. */
  val flow: View = View.Layout(Seq(speed, plots), Direction.Vertical)

  /** The map of the globe above, the plots below. */
  val globe: View = View.Layout(Seq(map, plots), Direction.Vertical)
