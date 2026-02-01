package physis.models.lorenz

import physis.view.{Channel, Direction, View}

/** How Physis Lab draws the Lorenz system. Plain data from physis-view; no toolkit. */
object LorenzViews:
  val x: Channel = Channel.StateAt(0, "x")
  val y: Channel = Channel.StateAt(1, "y")
  val z: Channel = Channel.StateAt(2, "z")

  /** The attractor: a rotatable 3D trajectory with a 20 000-point trail coloured by age. */
  val trajectory: View = View.Trajectory(x, y, Some(z))

  /** x, y and z over the last 20 simulated seconds. */
  val coordinates: View = View.Plot(Seq(x, y, z), window = 20.0, title = "Coordinates")

  /** Trajectory above, coordinates below. */
  val default: View = View.Layout(Seq(trajectory, coordinates), Direction.Vertical)
