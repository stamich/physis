package physis.models.pendulum

import physis.view.{Bounds, Channel, Direction, Rgb, SceneInput, Shape, TrailSpec, View}

/**
 * How Physis Lab draws the double pendulum: the mechanism with a trail of the outer bob, and the
 * relative energy drift. Plain data from physis-view; no toolkit.
 *
 * The drawing reads lengths and masses from the current parameters, so it follows live changes.
 */
object PendulumViews:
  private val Rod = Rgb.bytes(70, 70, 80)
  private val Bob1 = Rgb.bytes(31, 119, 180)
  private val Bob2 = Rgb.bytes(214, 39, 40)
  private val Pivot = Rgb.bytes(40, 40, 40)

  /** Position of the inner bob for angles in radians: (l1 sin θ1, −l1 cos θ1). */
  def inner(l1: Double, theta1: Double): (Double, Double) =
    (l1 * math.sin(theta1), -l1 * math.cos(theta1))

  /** Position of the outer bob. */
  def outer(l1: Double, l2: Double, theta1: Double, theta2: Double): (Double, Double) =
    val (x1, y1) = inner(l1, theta1)
    (x1 + l2 * math.sin(theta2), y1 - l2 * math.cos(theta2))

  /** Pivot, rods and bobs for the current state; bob areas scale with mass. */
  def draw(in: SceneInput): Seq[Shape] =
    val l1 = in.param("l1")
    val l2 = in.param("l2")
    val m1 = in.param("m1")
    val m2 = in.param("m2")
    val (x1, y1) = inner(l1, in.state(0))
    val (x2, y2) = outer(l1, l2, in.state(0), in.state(1))
    val base = 0.05 * (l1 + l2)
    val heavier = math.max(m1, m2)
    def radius(m: Double) = base * math.sqrt(m / heavier)
    Seq(
      Shape.Line(0, 0, x1, y1, 3.0, Rod),
      Shape.Line(x1, y1, x2, y2, 3.0, Rod),
      Shape.Circle(0, 0, base * 0.3, Pivot),
      Shape.Circle(x1, y1, radius(m1), Bob1),
      Shape.Circle(x2, y2, radius(m2), Bob2)
    )

  /** Trail of the outer bob. */
  val outerTrail: TrailSpec = TrailSpec(
    points = 2_000,
    x = in => outer(in.param("l1"), in.param("l2"), in.state(0), in.state(1))._1,
    y = in => outer(in.param("l1"), in.param("l2"), in.state(0), in.state(1))._2,
    color = Bob2
  )

  /** The mechanism, framed for the initial lengths. */
  def mechanism(p: PendulumParams): View =
    View.Scene2D(Bounds.square(1.1 * (p.l1 + p.l2)), draw, Some(outerTrail))

  /** Relative energy drift, probe 1 of [[Pendulum.probes]]. */
  val energyDrift: View =
    View.Plot(Seq(Channel.ProbeAt(1, "energy drift")), window = 30.0, title = "Energy drift")

  /** Mechanism left, energy drift right. */
  def default(p: PendulumParams): View =
    View.Layout(Seq(mechanism(p), energyDrift), Direction.Horizontal)
