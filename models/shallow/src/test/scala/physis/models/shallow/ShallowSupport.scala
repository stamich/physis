package physis.models.shallow

import physis.core.Stepping
import physis.core.run.{Physis, Run}

/** Helpers shared by the shallow-water suites. */
object ShallowSupport:
  type ShallowRun = Run[ShallowParams, ShallowState]

  def run(p: ShallowParams, stepping: Stepping = Shallow.stepping): ShallowRun =
    Physis.model(Shallow).withParams(p).stepping(stepping).probe(Shallow.probes*).build()

  /** A small basin; the defaults are quiet so that a test sets what it needs. */
  def params(
      gravity: Double = 9.81,
      depth: Double = 4000.0,
      dx: Double = 20_000.0,
      f0: Double = 0.0,
      start: Int = 0,
      amp: Double = 0.0,
      nx: Int = 40,
      ny: Int = 30
  ): ShallowParams = ShallowParams(gravity, depth, dx, f0, start, amp, nx, ny)

  /** Adds a Gaussian hump of height `amp` metres, `sigma` cells wide, at cell position `(x, y)`. */
  def hump(s: ShallowState, x: Double, y: Double, sigma: Double, amp: Double): Unit =
    for j <- 0 until s.mac.ny; i <- 0 until s.mac.nx do
      val dx = i + 0.5 - x
      val dy = j + 0.5 - y
      s.eta(i, j) += amp * math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))

  /** The volume of water above rest, in m³. */
  def volume(s: ShallowState): Double = s.eta.sum * s.mac.h * s.mac.h
