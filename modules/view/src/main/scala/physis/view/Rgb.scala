package physis.view

/**
 * An sRGB colour with components in [0, 1] and an opacity.
 *
 * Toolkit-free on purpose: renderers convert it to their own colour type.
 */
final case class Rgb(r: Double, g: Double, b: Double, opacity: Double = 1.0):
  require(
    Seq(r, g, b, opacity).forall(c => c >= 0.0 && c <= 1.0),
    s"colour components must be in [0, 1]: $this"
  )

  /** The same colour with another opacity. */
  def withOpacity(a: Double): Rgb = copy(opacity = a)

  /** `#rrggbb`, ignoring opacity. */
  def hex: String =
    def byte(c: Double) = f"${math.round(c * 255).toInt}%02x"
    s"#${byte(r)}${byte(g)}${byte(b)}"

object Rgb:
  /** Colour from 8-bit components. */
  def bytes(r: Int, g: Int, b: Int): Rgb = Rgb(r / 255.0, g / 255.0, b / 255.0)

  val Black: Rgb = Rgb(0, 0, 0)
  val White: Rgb = Rgb(1, 1, 1)
  val Grey: Rgb = Rgb(0.5, 0.5, 0.5)
