package physis.models.boids

import physis.view.{Channel, Colormap, Direction, View}

/** How Physis Lab draws Boids. Plain data from physis-view; no toolkit. */
object BoidsViews:
  /** The flock in its world, each boid coloured by heading. */
  val flock: View = View.Particles(colormap = Colormap.Cyclic, title = "Flock")

  /** Polarization in [[Boids.probes]] order: it is probe 0. */
  val polarization: Channel = Channel.ProbeAt(0, "polarization")

  /** Polarization over the last 10 time units (600 steps). */
  val order: View = View.Plot(Seq(polarization), window = 10.0, title = "Order")

  /** The flock above, its order parameter below. */
  val default: View = View.Layout(Seq(flock, order), Direction.Vertical)
