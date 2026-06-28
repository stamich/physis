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

  val beachRunup: ShallowPreset = ShallowPreset(
    "Beach run-up",
    "A solitary wave, 6 m high in a sea 100 m deep, runs in over a plane beach and up it: " +
      "the water floods the shore, stops, runs back and the shoreline retreats",
    Map(
      "gravity" -> 9.81,
      "depth" -> 100.0,
      "dx" -> 2_500.0,
      "f0" -> 0.0,
      "beta" -> 0.0,
      "friction" -> 0.0,
      "nonlinear" -> 1.0,
      "flooding" -> 1.0,
      "dryDepth" -> 0.5,
      "slope" -> 0.002,
      "edges" -> 2.0,
      "sponge" -> 8.0,
      "seabed" -> 3.0,
      "start" -> 3.0,
      "amp" -> 6.0,
      "nx" -> 120.0,
      "ny" -> 40.0
    ),
    none
  )

  val harbourFlood: ShallowPreset = ShallowPreset(
    "Harbour flood",
    "A solitary wave, 8 m high in a sea 100 m deep, comes ashore at a bay: the bay deepens " +
      "the water in front of the shore and the flood reaches further inland there than beside it",
    Map(
      "gravity" -> 9.81,
      "depth" -> 100.0,
      "dx" -> 2_500.0,
      "f0" -> 0.0,
      "beta" -> 0.0,
      "friction" -> 0.0,
      "nonlinear" -> 1.0,
      "flooding" -> 1.0,
      "dryDepth" -> 0.5,
      "slope" -> 0.002,
      "edges" -> 2.0,
      "sponge" -> 8.0,
      "seabed" -> 4.0,
      "start" -> 3.0,
      "amp" -> 8.0,
      "nx" -> 120.0,
      "ny" -> 60.0
    ),
    none
  )

  /** Where the place lies in a picture of `nx` by `ny` cells of the band to 80° (cell units). */
  private def cellOf(nx: Int, ny: Int, lon: Double, lat: Double): (Double, Double) =
    ((lon + 180.0) / 360.0 * nx, (80.0 - lat) / 160.0 * ny)

  private def globe(extra: (String, Double)*): Map[String, Double] =
    Map(
      "geometry" -> 1.0,
      "latMax" -> 80.0,
      "radius" -> 6.371e6,
      "rotation" -> 7.292e-5,
      "gravity" -> 9.81,
      "friction" -> 0.0,
      "edges" -> 1.0,
      "seabed" -> 0.0,
      "flooding" -> 0.0,
      "polar" -> 0.0
    ) ++ extra

  val pacific: ShallowPreset = ShallowPreset(
    "Pacific tsunami",
    "A rise of the sea floor off Japan on the whole globe, an ocean 4 km deep with no " +
      "continents: the waves spread over a sphere at 713 km/h, and the probe 'arrival' reports " +
      "when they reach a point near Hawaii. Quake paints more sources",
    globe(
      "depth" -> 4000.0,
      "nonlinear" -> 1.0,
      "start" -> 0.0,
      "amp" -> 0.0,
      "nx" -> 240.0,
      "ny" -> 106.0,
      "watchLon" -> -155.5,
      "watchLat" -> 19.7,
      "arrivalLevel" -> 0.01
    ),
    (nx, ny) =>
      val (x, y) = cellOf(nx, ny, 143.0, 38.0)
      Vector(Pulse("height", x, y, 2.0, 3.0))
  )

  val equatorial: ShallowPreset = ShallowPreset(
    "Equatorial Kelvin wave",
    "A bump on the equator of a rotating globe: the half that goes east stays at the equator " +
      "(a Kelvin wave, at sqrt(g h) all the way round), the rest spreads out and curves",
    globe(
      "depth" -> 4000.0,
      "nonlinear" -> 0.0,
      "start" -> 0.0,
      "amp" -> 0.0,
      "nx" -> 240.0,
      "ny" -> 106.0,
      "watchLon" -> 180.0,
      "watchLat" -> 0.0,
      "arrivalLevel" -> 0.01
    ),
    (nx, ny) =>
      val (x, y) = cellOf(nx, ny, 0.0, 0.0)
      Vector(Pulse("height", x, y, 4.0, 1.0))
  )

  val rossbyHaurwitz: ShallowPreset = ShallowPreset(
    "Rossby-Haurwitz wave",
    "Williamson's test 6: a wave of wave number 4 that moves east through the westerlies " +
      "(about 0.8 of the speed of the barotropic theory in this scheme)",
    globe(
      "depth" -> 8000.0,
      "nonlinear" -> 1.0,
      "start" -> 6.0,
      "amp" -> 7.848,
      "nx" -> 180.0,
      "ny" -> 80.0,
      "arrivalLevel" -> 0.0
    ),
    none
  )

  val all: Vector[ShallowPreset] =
    Vector(
      tsunami,
      adjustment,
      rossby,
      kelvin,
      harbour,
      beachRunup,
      harbourFlood,
      pacific,
      equatorial,
      rossbyHaurwitz
    )
