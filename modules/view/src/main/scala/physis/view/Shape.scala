package physis.view

/** Scene primitives in model coordinates (y up), drawn by a [[View.Scene2D]] renderer. */
enum Shape:
  /** A straight segment; `width` in pixels. */
  case Line(x1: Double, y1: Double, x2: Double, y2: Double, width: Double, color: Rgb)

  /** A filled circle; `r` in model units. */
  case Circle(cx: Double, cy: Double, r: Double, fill: Rgb)

  /** An open polyline through `xs(i), ys(i)`; `width` in pixels. */
  case Polyline(xs: Array[Double], ys: Array[Double], width: Double, color: Rgb)
