package physis.models.wave

import physis.core.run.{Disc, Intervention, Op}
import physis.core.run.Intervention.{Brush, Pulse}

/**
 * A named setting of the wave model: parameters, plus the walls and pulses painted into the
 * grid before the first step. The painted part is a list of ordinary interventions, applied at
 * step 0, so a preset appears in the manifest like anything the user painted by hand.
 *
 * Parameters include the restart-only `edge`, so applying a preset means starting a new run.
 *
 * @param params        parameter values it sets; all others keep their default
 * @param interventions what to paint, for a grid of the given size
 */
final case class WavePreset(
    name: String,
    doc: String,
    params: Map[String, Double],
    interventions: (Int, Int) => Vector[Intervention]
)

object WavePresets:
  private def wall(x: Double, y: Double, r: Double): Intervention =
    Brush("wall", Disc(x, y, r), Op.Assign, 1.0)

  /** Overlapping wall discs along a horizontal or vertical line, `step` cells apart. */
  private def line(x0: Double, y0: Double, x1: Double, y1: Double): Vector[Intervention] =
    val length = math.hypot(x1 - x0, y1 - y0)
    val n = math.max(1, math.ceil(length / 2.0).toInt)
    Vector.tabulate(n + 1) { k =>
      val f = k.toDouble / n
      wall(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 2.6)
    }

  val rippleTank: WavePreset = WavePreset(
    "Ripple tank",
    "A reflective box and one pulse",
    Map("c" -> 1.0, "damping" -> 0.002, "edge" -> 1.0, "amplitude" -> 0.0),
    (nx, ny) => Vector(Pulse("u", nx * 0.35, ny * 0.5, 4.0, 1.0))
  )

  val doubleSlit: WavePreset = WavePreset(
    "Double slit",
    "A wall with two openings in front of a steady source; absorbing edges",
    Map(
      "c" -> 1.0,
      "damping" -> 0.0,
      "edge" -> 3.0,
      "sponge" -> 24.0,
      "frequency" -> 0.2,
      "amplitude" -> 1.0
    ),
    (nx, ny) =>
      val x = nx * 0.68
      val mid = ny * 0.5
      val gap = ny * 0.1
      val slit = 6.0
      val top = line(x, 0.0, x, mid - gap - slit)
      val between = line(x, mid - gap + slit, x, mid + gap - slit)
      val bottom = line(x, mid + gap + slit, x, ny.toDouble)
      top ++ between ++ bottom
  )

  val echoChamber: WavePreset = WavePreset(
    "Echo chamber",
    "Almost no damping, free edges and two blocks",
    Map("c" -> 1.0, "damping" -> 0.0005, "edge" -> 2.0, "amplitude" -> 0.0),
    (nx, ny) =>
      Vector(
        wall(nx * 0.5, ny * 0.3, 12.0),
        wall(nx * 0.65, ny * 0.7, 9.0),
        Pulse("u", nx * 0.25, ny * 0.5, 3.0, 1.0)
      )
  )

  val openWater: WavePreset = WavePreset(
    "Open water",
    "Absorbing edges, no walls, a gentle source",
    Map(
      "c" -> 1.0,
      "damping" -> 0.0,
      "edge" -> 3.0,
      "sponge" -> 24.0,
      "frequency" -> 0.12,
      "amplitude" -> 0.8
    ),
    (_, _) => Vector.empty
  )

  val waveguide: WavePreset = WavePreset(
    "Waveguide",
    "A pulse in a painted channel",
    Map("c" -> 1.0, "damping" -> 0.0, "edge" -> 1.0, "amplitude" -> 0.0),
    (nx, ny) =>
      val half = 14.0
      line(nx * 0.1, ny * 0.5 - half, nx * 0.92, ny * 0.5 - half) ++
        line(nx * 0.1, ny * 0.5 + half, nx * 0.92, ny * 0.5 + half) ++
        Vector(Pulse("u", nx * 0.2, ny * 0.5, 3.5, 1.0))
  )

  val all: Vector[WavePreset] = Vector(rippleTank, doubleSlit, echoChamber, openWater, waveguide)
