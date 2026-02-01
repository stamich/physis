package physis.view

/**
 * Named colour scales for trails, plots and (from 0.3) fields, each a 256-entry table built once.
 *
 * The tables interpolate published anchor colours linearly in sRGB: the ten-colour palettes of
 * viridis and cividis (matplotlib), the eleven-colour RdBu palette (ColorBrewer) for the
 * diverging map, and a closed loop of eight colours for the cyclic map.
 */
enum Colormap:
  /** Perceptually uniform, dark purple through teal to yellow. */
  case Viridis

  /** Perceptually uniform and colour-vision-deficiency friendly, dark blue to yellow. */
  case Cividis

  /** Dark blue through white to dark red, for signed quantities centred at 0.5. */
  case Diverging

  /** Wraps around (`apply(0) == apply(1)`), for angles and phases. */
  case Cyclic

  /** Colour at `t` in [0, 1]; values outside are clamped, NaN gives the first colour. */
  def apply(t: Double): Rgb =
    val i = if t > 0.0 then math.min(255, (t * 255 + 0.5).toInt) else 0
    Colormap.tables(ordinal)(i)

  /** All 256 entries, from t = 0 to t = 1. */
  def table: IndexedSeq[Rgb] = Colormap.tables(ordinal)

object Colormap:
  /** Number of entries per table. */
  val Size = 256

  private def anchors(cm: Colormap): Vector[String] = cm match
    case Viridis =>
      Vector(
        "440154",
        "482878",
        "3e4989",
        "31688e",
        "26828e",
        "1f9e89",
        "35b779",
        "6ece58",
        "b5de2b",
        "fde725"
      )
    case Cividis =>
      Vector(
        "00204d",
        "00336f",
        "39486b",
        "575d6d",
        "707173",
        "8a8779",
        "a69d75",
        "c4b56c",
        "e4cf5b",
        "ffea46"
      )
    case Diverging =>
      Vector(
        "053061",
        "2166ac",
        "4393c3",
        "92c5de",
        "d1e5f0",
        "f7f7f7",
        "fddbc7",
        "f4a582",
        "d6604d",
        "b2182b",
        "67001f"
      )
    case Cyclic =>
      Vector(
        "e1d8e1",
        "8f9fc9",
        "5b5aa8",
        "3f2366",
        "301437",
        "6f2048",
        "ab4f3c",
        "d29a83",
        "e1d8e1"
      )

  private lazy val tables: Vector[Vector[Rgb]] = Colormap.values.toVector.map(build)

  private def build(cm: Colormap): Vector[Rgb] =
    val points = anchors(cm).map(parse)
    val segments = points.size - 1
    Vector.tabulate(Size) { i =>
      val t = i.toDouble / (Size - 1) * segments
      val k = math.min(t.toInt, segments - 1)
      val u = t - k
      val (a, b) = (points(k), points(k + 1))
      Rgb(mix(a.r, b.r, u), mix(a.g, b.g, u), mix(a.b, b.b, u))
    }

  private def mix(a: Double, b: Double, u: Double): Double =
    math.max(0.0, math.min(1.0, a + (b - a) * u))

  private def parse(hex: String): Rgb =
    def byte(i: Int) = Integer.parseInt(hex.substring(i, i + 2), 16)
    Rgb.bytes(byte(0), byte(2), byte(4))

  /** CIE L* lightness of an sRGB colour, in [0, 100]. */
  def lightness(c: Rgb): Double =
    def linear(v: Double) = if v <= 0.04045 then v / 12.92 else math.pow((v + 0.055) / 1.055, 2.4)
    val y = 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
    val f = if y > 216.0 / 24389 then math.cbrt(y) else (24389.0 / 27 * y + 16) / 116
    116 * f - 16
