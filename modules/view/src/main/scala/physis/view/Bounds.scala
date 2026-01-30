package physis.view

/** An axis-aligned rectangle in model coordinates, y pointing up. */
final case class Bounds(xMin: Double, xMax: Double, yMin: Double, yMax: Double):
  require(xMax > xMin && yMax > yMin, s"empty bounds: $this")

  def width: Double = xMax - xMin
  def height: Double = yMax - yMin
  def centreX: Double = (xMin + xMax) / 2
  def centreY: Double = (yMin + yMax) / 2

  /** Whether (x, y) lies inside or on the edge. */
  def contains(x: Double, y: Double): Boolean =
    x >= xMin && x <= xMax && y >= yMin && y <= yMax

  /** The bounds grown by `fraction` of their size on every side. */
  def padded(fraction: Double): Bounds =
    val dx = width * fraction
    val dy = height * fraction
    Bounds(xMin - dx, xMax + dx, yMin - dy, yMax + dy)

object Bounds:
  /** A square of half-width `half` centred on the origin. */
  def square(half: Double): Bounds = Bounds(-half, half, -half, half)
