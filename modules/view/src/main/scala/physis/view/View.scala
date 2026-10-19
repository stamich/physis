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

  /**
   * A scalar field, one slice of the exported state, as a colour-mapped image (row 0 on top),
   * with a colour bar and a read-out of the cell under the pointer.
   *
   * @param slice    name of the snapshot slice that holds the field
   * @param range    which values get the first and the last colour
   * @param colormap colours
   * @param title    shown above the image; empty for none
   * @param overlay  optional slice of the same size drawn on top as a flat grey wherever its value
   *                 is 1 or more (walls); empty cells show the field
   */
  case Heatmap(
      slice: String,
      range: ValueRange = ValueRange.Auto,
      colormap: Colormap = Colormap.Viridis,
      title: String = "",
      overlay: Option[String] = None
  )

  /**
   * Particles in a periodic world, one triangle per particle pointing along its velocity and
   * coloured by heading (the world is `0..width` by `0..height`, y up, read from parameters).
   *
   * @param x           slice with the x positions
   * @param y           slice with the y positions
   * @param vx          slice with the x velocities
   * @param vy          slice with the y velocities
   * @param widthParam  name of the parameter holding the world width
   * @param heightParam name of the parameter holding the world height
   * @param colormap    colours of the heading (a cyclic map fits)
   * @param title       shown above the world; empty for none
   */
  case Particles(
      x: String = "x",
      y: String = "y",
      vx: String = "vx",
      vy: String = "vy",
      widthParam: String = "width",
      heightParam: String = "height",
      colormap: Colormap = Colormap.Cyclic,
      title: String = ""
  )

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
