package physis.models.wave

import physis.view.{Channel, Colormap, Direction, ValueRange, View}

/** How Physis Lab draws the wave model. Plain data from physis-view; no toolkit. */
object WaveViews:
  /** The wave with a diverging map centred at zero, walls drawn over it in flat grey. */
  val field: View = View.Heatmap(
    "u",
    ValueRange.SymmetricAuto,
    Colormap.Diverging,
    "wave",
    overlay = Some("wall")
  )

  /** Channels of the probes in [[Wave.probes]] order: energy, max_abs, mean, source. */
  val energy: Channel = Channel.ProbeAt(0, "energy")
  val maxAbs: Channel = Channel.ProbeAt(1, "max |u|")

  /** Total energy over the last 300 time units. */
  val energyPlot: View = View.Plot(Seq(energy), window = 300.0, title = "Energy")

  /** Largest absolute wave value over the last 300 time units. */
  val amplitudePlot: View = View.Plot(Seq(maxAbs), window = 300.0, title = "Amplitude")

  /** The two plots side by side. */
  val plots: View = View.Layout(Seq(energyPlot, amplitudePlot), Direction.Horizontal)

  /** The field above, the plots below. */
  val default: View = View.Layout(Seq(field, plots), Direction.Vertical)
