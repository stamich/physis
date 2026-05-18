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
      beta: Double = 0.0,
      friction: Double = 0.0,
      nonlinear: Int = 0,
      edges: Int = Shallow.Basin,
      sponge: Int = 10,
      seabed: Int = 0,
      start: Int = 0,
      amp: Double = 0.0,
      nx: Int = 40,
      ny: Int = 30
  ): ShallowParams = ShallowParams(
    gravity = gravity,
    depth = depth,
    dx = dx,
    f0 = f0,
    beta = beta,
    friction = friction,
    nonlinear = nonlinear,
    edges = edges,
    sponge = sponge,
    seabed = seabed,
    start = start,
    amp = amp,
    nx = nx,
    ny = ny
  )

  /**
   * Adds a Gaussian hump of height `amp` metres, `sigma` cells wide, at cell position `(x, y)`;
   * it wraps around the sides of a channel.
   */
  def hump(s: ShallowState, x: Double, y: Double, sigma: Double, amp: Double): Unit =
    val n = s.mac.nx
    for j <- 0 until s.mac.ny; i <- 0 until n do
      var dx = i + 0.5 - x
      if s.wrapX then dx -= n * math.rint(dx / n)
      val dy = j + 0.5 - y
      s.eta(i, j) += amp * math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))

  /** Adds a Gaussian ridge of height `amp` metres along `y` at cell position `x`: a 1D wave. */
  def ridge(s: ShallowState, x: Double, sigma: Double, amp: Double): Unit =
    for j <- 0 until s.mac.ny; i <- 0 until s.mac.nx do
      val dx = i + 0.5 - x
      s.eta(i, j) += amp * math.exp(-(dx * dx) / (2.0 * sigma * sigma))

  /** The volume of water above rest, in m³. */
  def volume(s: ShallowState): Double = s.eta.sum * s.mac.h * s.mac.h
