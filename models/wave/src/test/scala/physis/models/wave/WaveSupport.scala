package physis.models.wave

import physis.core.Stepping
import physis.core.run.{Disc, Op, Physis, Run}
import physis.core.run.Intervention.Brush

/** Helpers shared by the wave suites. */
object WaveSupport:
  type WaveRun = Run[WaveParams, WaveState]

  def run(p: WaveParams, stepping: Stepping = Wave.stepping): WaveRun =
    Physis.model(Wave).withParams(p).stepping(stepping).probe(Wave.probes*).build()

  /** The setup of tools/wave_reference.py `box`: a wall of discs, a bump, damping and a source. */
  def box(edge: Int): WaveRun =
    val p = WaveParams(
      c = 1.0,
      damping = 0.02,
      edge = edge,
      sponge = 16,
      frequency = 0.2,
      amplitude = 0.5,
      nx = 64,
      ny = 64
    )
    val r = run(p)
    for k <- 0 until 12 do
      val _ = r.intervene(Brush("wall", Disc(40.5, 8.5 + 4.0 * k, 2.6), Op.Assign, 1.0))
    val _ = r.intervene(Brush("u", Disc(20.3, 28.7, 5.0), Op.Assign, 1.0))
    r

  /** Sets the run's two levels to a standing mode that is exact for the leapfrog scheme. */
  def standingMode(r: WaveRun, kx: Double, ky: Double, omega: Double): Unit =
    val s = r.state
    for j <- 0 until s.grid.ny; i <- 0 until s.grid.nx do
      val shape = math.cos(kx * (i + 0.5)) * math.cos(ky * (j + 0.5))
      s.u(i, j) = shape
      s.previous(i, j) = shape * math.cos(omega * Wave.Dt)

  /** The frequency the leapfrog scheme gives a mode: sin²(ω dt / 2) = C² (sin² + sin²). */
  def discreteOmega(c: Double, kx: Double, ky: Double): Double =
    val courant = c * Wave.Dt
    val s = courant * courant * (math.pow(math.sin(kx / 2), 2) + math.pow(math.sin(ky / 2), 2))
    2.0 * math.asin(math.sqrt(s)) / Wave.Dt

  /** Projection of the wave on the cosine mode, a single number that follows the mode in time. */
  def projection(s: WaveState, kx: Double, ky: Double): Double =
    var sum = 0.0
    for j <- 0 until s.grid.ny; i <- 0 until s.grid.nx do
      sum += s.u(i, j) * math.cos(kx * (i + 0.5)) * math.cos(ky * (j + 0.5))
    sum
