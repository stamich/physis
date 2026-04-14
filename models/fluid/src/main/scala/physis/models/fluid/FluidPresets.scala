package physis.models.fluid

import physis.core.run.{Disc, Intervention, Op}
import physis.core.run.Intervention.{Brush, Pulse}

/**
 * A named setting of the fluid model: parameters, plus the walls, dye and impulses painted into
 * the grid before the first step. The painted part is a list of ordinary interventions, applied
 * at step 0, so a preset appears in the manifest like anything the user painted by hand.
 *
 * Parameters include the restart-only `edge`, `nx` and `ny`, so applying a preset means
 * starting a new run.
 *
 * @param params        parameter values it sets; all others keep their default
 * @param interventions what to paint, for a grid of the given size
 */
final case class FluidPreset(
    name: String,
    doc: String,
    params: Map[String, Double],
    interventions: (Int, Int) => Vector[Intervention]
)

object FluidPresets:
  private def dye(x: Double, y: Double, r: Double): Intervention =
    Brush("dye", Disc(x, y, r), Op.Assign, 1.0)

  private def wall(x: Double, y: Double, r: Double): Intervention =
    Brush("wall", Disc(x, y, r), Op.Assign, 1.0)

  /** Overlapping dye discs of radius `r` along a line, two cells apart. */
  private def stripe(x0: Double, y0: Double, x1: Double, y1: Double, r: Double) =
    val length = math.hypot(x1 - x0, y1 - y0)
    val n = math.max(1, math.ceil(length / 2.0).toInt)
    Vector.tabulate(n + 1) { k =>
      val f = k.toDouble / n
      dye(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, r)
    }

  val cavity: FluidPreset = FluidPreset(
    "Cavity",
    "A closed box whose lid moves: Reynolds number 100, three layers of dye",
    Map(
      "viscosity" -> 0.64,
      "dyeDiffusion" -> 0.0,
      "buoyancy" -> 0.0,
      "lid" -> 1.0,
      "inflow" -> 0.0,
      "edge" -> 1.0,
      "nx" -> 64.0,
      "ny" -> 64.0,
      "tracers" -> 1500.0
    ),
    (nx, ny) =>
      Vector(0.25, 0.5, 0.75).flatMap(f =>
        stripe(nx * 0.08, ny * f, nx * 0.92, ny * f, 2.0)
      )
  )

  val cylinderWake: FluidPreset = FluidPreset(
    "Cylinder wake",
    "A channel with a cylinder at Reynolds number 150: the wake sheds a street of vortices",
    Map(
      "viscosity" -> 0.096,
      "dyeDiffusion" -> 0.0,
      "buoyancy" -> 0.0,
      "lid" -> 0.0,
      "inflow" -> 1.0,
      "edge" -> 2.0,
      "nx" -> 192.0,
      "ny" -> 72.0,
      "tracers" -> 3000.0
    ),
    (nx, ny) =>
      // a little off the axis, so that the street starts soon
      wall(nx * 0.2, ny * 0.5 + 0.3, ny / 10.0) +:
        stripe(nx * 0.04, ny * 0.06, nx * 0.04, ny * 0.94, 1.6)
  )

  val smokePlume: FluidPreset = FluidPreset(
    "Smoke plume",
    "Smoke that rises in a closed box and rolls up into mushrooms",
    Map(
      "viscosity" -> 0.01,
      "dyeDiffusion" -> 0.0,
      "buoyancy" -> 0.1,
      "lid" -> 0.0,
      "inflow" -> 0.0,
      "edge" -> 1.0,
      "nx" -> 96.0,
      "ny" -> 128.0
    ),
    (nx, ny) =>
      Vector(dye(nx * 0.5, ny * 0.1, 6.0), dye(nx * 0.5 + 5.0, ny * 0.1 + 3.0, 3.0))
  )

  val vortexPair: FluidPreset = FluidPreset(
    "Vortex pair",
    "Two opposite jets in a periodic box roll up into a pair of vortices",
    Map(
      "viscosity" -> 0.02,
      "dyeDiffusion" -> 0.0,
      "buoyancy" -> 0.0,
      "lid" -> 0.0,
      "inflow" -> 0.0,
      "edge" -> 0.0,
      "nx" -> 128.0,
      "ny" -> 128.0,
      "tracers" -> 3000.0
    ),
    (nx, ny) =>
      Vector(
        Pulse("u", nx * 0.3, ny * 0.5 - 6.0, 4.0, 0.8),
        Pulse("u", nx * 0.3, ny * 0.5 + 6.0, 4.0, -0.8),
        dye(nx * 0.3, ny * 0.5 - 6.0, 5.0),
        dye(nx * 0.3, ny * 0.5 + 6.0, 5.0)
      )
  )

  val all: Vector[FluidPreset] = Vector(cavity, cylinderWake, smokePlume, vortexPair)
