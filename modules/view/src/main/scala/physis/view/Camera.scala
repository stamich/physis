package physis.view

/** An axis-aligned box in model coordinates. */
final case class Bounds3(
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    zMin: Double,
    zMax: Double
):
  require(xMax >= xMin && yMax >= yMin && zMax >= zMin, s"inverted bounds: $this")

  def centreX: Double = (xMin + xMax) / 2
  def centreY: Double = (yMin + yMax) / 2
  def centreZ: Double = (zMin + zMax) / 2

  /** Half the length of the diagonal: the radius of the smallest sphere around the box centre. */
  def radius: Double =
    val dx = xMax - xMin
    val dy = yMax - yMin
    val dz = zMax - zMin
    math.sqrt(dx * dx + dy * dy + dz * dz) / 2

/**
 * Orbit camera with orthographic projection from model coordinates to view (pixel) coordinates.
 *
 * The camera looks at `centre` from a direction given by `yaw` (rotation about the model's z
 * axis) and `pitch` (elevation above the x-y plane). Model z points up on screen at zero pitch;
 * view y grows downwards, as in every 2D toolkit.
 *
 * Mutable and not thread-safe: it belongs to the thread that renders. Projection allocates
 * nothing; the rotation is cached when the angles change.
 */
final class Camera:
  private var yawAngle = Camera.DefaultYaw
  private var pitchAngle = Camera.DefaultPitch
  private var zoomFactor = 1.0
  private var cx, cy, cz = 0.0
  private var scale = 1.0
  private var width, height = 1.0
  private var cosYaw, sinYaw, cosPitch, sinPitch = 0.0
  updateRotation()

  def yaw: Double = yawAngle
  def pitch: Double = pitchAngle
  def zoom: Double = zoomFactor

  /** Size of the view in pixels. */
  def viewport(w: Double, h: Double): Unit =
    require(w > 0 && h > 0, "viewport must not be empty")
    scale *= math.min(w, h) / math.min(width, height)
    width = w
    height = h

  /**
   * Points the camera at the box centre and scales so the box fits in any orientation at zoom 1.
   * The zoom is kept, so a view can refit continuously while the user zooms.
   */
  def fit(bounds: Bounds3, margin: Double = 0.05): Unit =
    cx = bounds.centreX
    cy = bounds.centreY
    cz = bounds.centreZ
    val r = math.max(bounds.radius, 1e-12)
    scale = math.min(width, height) / 2 * (1 - margin) / r

  /** Back to zoom 1. */
  def resetZoom(): Unit = zoomFactor = 1.0

  /** Rotates by the given angles (radians). Pitch stays within ±89°. */
  def rotate(dYaw: Double, dPitch: Double): Unit =
    yawAngle = Camera.wrap(yawAngle + dYaw)
    pitchAngle = math.max(-Camera.MaxPitch, math.min(Camera.MaxPitch, pitchAngle + dPitch))
    updateRotation()

  /** Sets the orientation directly. */
  def orient(yaw: Double, pitch: Double): Unit =
    yawAngle = 0.0
    pitchAngle = 0.0
    rotate(yaw, pitch)

  /** Multiplies the zoom by `factor`, keeping it within [0.05, 50]. */
  def zoomBy(factor: Double): Unit =
    require(factor > 0, "zoom factor must be positive")
    zoomFactor = math.max(0.05, math.min(50.0, zoomFactor * factor))

  /** Horizontal view coordinate of a model point. */
  def viewX(x: Double, y: Double, z: Double): Double =
    width / 2 + scale * zoomFactor * right(x - cx, y - cy)

  /** Vertical view coordinate of a model point (grows downwards). */
  def viewY(x: Double, y: Double, z: Double): Double =
    height / 2 - scale * zoomFactor * up(x - cx, y - cy, z - cz)

  /** Distance along the viewing direction from the centre plane; larger is farther away. */
  def depth(x: Double, y: Double, z: Double): Double =
    cosPitch * ahead(x - cx, y - cy) - sinPitch * (z - cz)

  // Yaw turns about the model z axis: `right` is the screen-horizontal component and `ahead` the
  // horizontal component away from the viewer. Pitch then tilts the viewer upwards, so points
  // farther away rise on screen.
  private def right(dx: Double, dy: Double): Double = cosYaw * dx - sinYaw * dy

  private def ahead(dx: Double, dy: Double): Double = sinYaw * dx + cosYaw * dy

  private def up(dx: Double, dy: Double, dz: Double): Double =
    cosPitch * dz + sinPitch * ahead(dx, dy)

  private def updateRotation(): Unit =
    cosYaw = math.cos(yawAngle)
    sinYaw = math.sin(yawAngle)
    cosPitch = math.cos(pitchAngle)
    sinPitch = math.sin(pitchAngle)

object Camera:
  /** A three-quarter view, a good default for attractors. */
  val DefaultYaw: Double = math.toRadians(-35)
  val DefaultPitch: Double = math.toRadians(20)
  private val MaxPitch = math.toRadians(89)

  private def wrap(a: Double): Double =
    val twoPi = 2 * math.Pi
    val w = a % twoPi
    if w > math.Pi then w - twoPi else if w <= -math.Pi then w + twoPi else w
