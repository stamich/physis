package physis.view

/** Named colour scales for trails, plots and (from 0.3) fields. */
enum Colormap:
  /** Perceptually uniform, dark blue to yellow. */
  case Viridis

  /** Perceptually uniform and colour-vision-deficiency friendly, blue to yellow. */
  case Cividis

  /** Blue through white to red, for signed quantities. */
  case Diverging

  /** Wraps around, for angles and phases. */
  case Cyclic
