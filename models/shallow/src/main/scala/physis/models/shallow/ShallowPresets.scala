package physis.models.shallow

import physis.core.run.{Disc, Intervention, Op}
import physis.core.run.Intervention.{Brush, Pulse}

/**
 * A named setting of the shallow-water model: parameters, plus what is painted into the sea
 * before the first step (a raised surface, land). The painted part is a list of ordinary
 * interventions, applied at step 0, so a preset appears in the manifest like anything painted by
 * hand. Parameters include the restart-only `edges`, `seabed`, `start`, `dx`, `nx` and `ny`, so
 * applying a preset starts a new run.
 *
 * @param params        parameter values it sets; all others keep their default
 * @param interventions what to paint, for a grid of the given size
 */
final case class ShallowPreset(
    name: String,
    doc: String,
    params: Map[String, Double],
    interventions: (Int, Int) => Vector[Intervention]
)

object ShallowPresets:
  private val none: (Int, Int) => Vector[Intervention] = (_, _) => Vector.empty

  val tsunami: ShallowPreset = ShallowPreset(
    "Tsunami",
    "A rise of the sea floor, 2 m high and 1 000 km long, in an ocean 4 km deep: the waves " +
      "cross it at 713 km/h, slow down on the shelf, grow there and leave through the absorbing edges",
    Map(
      "gravity" -> 9.81,
      "depth" -> 4000.0,
      "dx" -> 20_000.0,
      "f0" -> 5e-5,
      "beta" -> 0.0,
      "friction" -> 0.0,
      "nonlinear" -> 1.0,
      "edges" -> 2.0,
      "sponge" -> 10.0,
      "seabed" -> 1.0,
      "start" -> 0.0,
      "amp" -> 0.0,
      "nx" -> 160.0,
      "ny" -> 96.0
    ),
    (nx, ny) =>
      val fault = (0 to (0.5 * ny / 3.0).toInt).map(k => 0.25 * ny + 3.0 * k)
      fault.map(y => Pulse("height", nx * 0.25, y, 2.5, 0.9)).toVector
  )

  val adjustment: ShallowPreset = ShallowPreset(
    "Adjustment",
    "A step in the surface in water 200 m deep on a rotating plane: the water does not run " +
      "downhill but settles into a current along the step, within the Rossby radius c / f",
    Map(
      "gravity" -> 9.81,
      "depth" -> 200.0,
      "dx" -> 10_000.0,
      "f0" -> 1e-4,
      "beta" -> 0.0,
      "friction" -> 0.0,
      "nonlinear" -> 0.0,
      "edges" -> 0.0,
      "sponge" -> 10.0,
      "seabed" -> 0.0,
      "start" -> 1.0,
      "amp" -> 0.5,
      "nx" -> 96.0,
      "ny" -> 128.0
    ),
    none
  )

  val rossby: ShallowPreset = ShallowPreset(
    "Rossby wave",
    "A balanced wave on a beta plane: the pattern drifts west, slowly, while the water " +
      "itself moves along the lines of equal height",
    Map(
      "gravity" -> 9.81,
      "depth" -> 500.0,
      "dx" -> 50_000.0,
      "f0" -> 1e-4,
      "beta" -> 4e-11,
      "friction" -> 0.0,
      "nonlinear" -> 0.0,
      "edges" -> 0.0,
      "sponge" -> 10.0,
      "seabed" -> 0.0,
      "start" -> 2.0,
      "amp" -> 0.1,
      "nx" -> 96.0,
      "ny" -> 48.0
    ),
    none
  )

  val kelvin: ShallowPreset = ShallowPreset(
    "Kelvin wave",
    "A bump against the west coast of a closed basin: the wave runs south along the coast, " +
      "trapped within the Rossby radius, and goes round the basin counter-clockwise",
    Map(
      "gravity" -> 9.81,
      "depth" -> 200.0,
      "dx" -> 10_000.0,
      "f0" -> 1e-4,
      "beta" -> 0.0,
      "friction" -> 0.0,
      "nonlinear" -> 0.0,
      "edges" -> 1.0,
      "sponge" -> 10.0,
      "seabed" -> 0.0,
      "start" -> 0.0,
      "amp" -> 0.0,
      "nx" -> 96.0,
      "ny" -> 200.0
    ),
    (nx, ny) => Vector(Pulse("height", 1.5, ny * 0.5, 3.0, 0.3))
  )

  val harbour: ShallowPreset = ShallowPreset(
    "Harbour",
    "A wave front in shallow water meets a breakwater with a gap and an island behind it: " +
      "the wave bends round the ends and spreads from the gap",
    Map(
      "gravity" -> 9.81,
      "depth" -> 40.0,
      "dx" -> 2_000.0,
      "f0" -> 0.0,
      "beta" -> 0.0,
      "friction" -> 0.0,
      "nonlinear" -> 1.0,
      "edges" -> 2.0,
      "sponge" -> 8.0,
      "seabed" -> 0.0,
      "start" -> 0.0,
      "amp" -> 0.0,
      "nx" -> 120.0,
      "ny" -> 80.0
    ),
    (nx, ny) =>
      val wall = nx * 0.55
      val gapLow = ny * 0.4
      val gapHigh = ny * 0.6
      val breakwater = (0 until ny by 2)
        .filter(j => j + 1.0 < gapLow || j + 1.0 > gapHigh)
        .map(j => Brush("land", Disc(wall, j + 1.0, 2.0), Op.Assign, 1.0))
      val island = Brush("land", Disc(nx * 0.78, ny * 0.5, 6.0), Op.Assign, 1.0)
      val front =
        (0 until ny by 4).map(j => Brush("height", Disc(nx * 0.12, j + 2.0, 3.0), Op.Assign, 1.0))
      breakwater.toVector ++ Vector(island) ++ front
  )

  val all: Vector[ShallowPreset] = Vector(tsunami, adjustment, rossby, kelvin, harbour)
