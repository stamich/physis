package physis.view

/**
 * What to draw for a model, as plain data.
 *
 * Models describe their views with these values; only a toolkit module (physis-fx in 0.2) turns
 * them into widgets, so the same description can drive another renderer later.
 */
enum View:
  /**
   * Phase-space trajectory of two or three channels with a fading trail. With `z` the points are
   * projected through an orbit [[Camera]].
   *
   * @param trail    number of most recent points kept
   * @param colormap colours of the trail
   * @param colorBy  what the colour encodes
   */
  case Trajectory(
      x: Channel,
      y: Channel,
      z: Option[Channel] = None,
      trail: Int = 20_000,
      colormap: Colormap = Colormap.Viridis,
      colorBy: TrailColor = TrailColor.Age
  )

  /** Scrolling time series of one or more channels over the last `window` simulated seconds. */
  case Plot(channels: Seq[Channel], window: Double, title: String = "")

  /**
   * A model-specific 2D drawing built from the current snapshot (rods, bobs, particles), in model
   * coordinates within `bounds`.
   */
  case Scene2D(bounds: Bounds, draw: SceneInput => Seq[Shape], trail: Option[TrailSpec] = None)

  /** Several views side by side or stacked; the host lays them out with equal shares. */
  case Layout(views: Seq[View], direction: Direction = Direction.Horizontal)

/** What a trajectory's colour encodes. */
enum TrailColor:
  /** Oldest points at the start of the colormap, newest at the end. */
  case Age

  /** The value of a channel, scaled to the trail's running range. */
  case By(channel: Channel)

/** Arrangement of a [[View.Layout]]. */
enum Direction:
  case Horizontal, Vertical

/**
 * A fading trail behind one point of a [[View.Scene2D]].
 *
 * @param points number of most recent positions kept
 * @param x      x of the traced point
 * @param y      y of the traced point
 * @param color  colour of the newest part; older parts fade out
 */
final case class TrailSpec(
    points: Int,
    x: SceneInput => Double,
    y: SceneInput => Double,
    color: Rgb = Rgb.Grey
):
  require(points > 1, "a trail needs at least two points")
