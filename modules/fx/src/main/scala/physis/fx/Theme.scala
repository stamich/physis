package physis.fx

import javafx.scene.paint.Color

import physis.view.{Colormap, Rgb}

/** Colours shared by the renderers, and conversions from toolkit-free view colours. */
object Theme:
  val Background: Color = Color.web("#111418")
  val Axis: Color = Color.web("#5a6270")
  val Grid: Color = Color.web("#232830")
  val Text: Color = Color.web("#c8ccd4")

  /** Line colours for plot channels, in order (Tableau 10). */
  val Series: IndexedSeq[Color] =
    Vector("#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f", "#edc948", "#b07aa1", "#ff9da7")
      .map(Color.web)

  /** JavaFX colour of a view colour. */
  def color(c: Rgb): Color = new Color(c.r, c.g, c.b, c.opacity)

  /**
   * `bands` colours sampled at the band centres of `colormap`; with `fade` the opacity rises
   * from 0.2 for the first band to 1 for the last, so old trail segments recede.
   */
  def bands(colormap: Colormap, bands: Int, fade: Boolean): IndexedSeq[Color] =
    Vector.tabulate(bands) { b =>
      val t = (b + 0.5) / bands
      val c = colormap(t)
      new Color(c.r, c.g, c.b, if fade then 0.2 + 0.8 * t else 1.0)
    }
