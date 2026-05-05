package physis.models.convection

import physis.core.run.Intervention
import physis.core.run.Intervention.Pulse

/**
 * A named setting of the convection model: parameters, plus the heat painted into the layer
 * before the first step. The painted part is a list of ordinary interventions, applied at step
 * 0, so a preset appears in the manifest like anything the user painted by hand. Parameters
 * include the restart-only `sides`, `nx`, `ny` and `kick`, so applying a preset starts a new run.
 *
 * @param params        parameter values it sets; all others keep their default
 * @param interventions what to paint, for a grid of the given size
 */
final case class ConvectionPreset(
    name: String,
    doc: String,
    params: Map[String, Double],
    interventions: (Int, Int) => Vector[Intervention]
)

object ConvectionPresets:
  private def heat(x: Double, y: Double, sigma: Double, amplitude: Double): Intervention =
    Pulse("heat", x, y, sigma, amplitude)

  private val none: (Int, Int) => Vector[Intervention] = (_, _) => Vector.empty

  val onset: ConvectionPreset = ConvectionPreset(
    "Onset",
    "A little above the critical Rayleigh number (about 1708): a small perturbation grows slowly into one pair of rolls",
    Map(
      "rayleigh" -> 3000.0,
      "prandtl" -> 1.0,
      "sides" -> 0.0,
      "nx" -> 96.0,
      "ny" -> 48.0,
      "kick" -> 0.005
    ),
    none
  )

  val quiet: ConvectionPreset = ConvectionPreset(
    "Quiet",
    "Below the critical Rayleigh number: the same perturbation dies away and heat only conducts",
    Map(
      "rayleigh" -> 1200.0,
      "prandtl" -> 1.0,
      "sides" -> 0.0,
      "nx" -> 96.0,
      "ny" -> 48.0,
      "kick" -> 0.05
    ),
    none
  )

  val rolls: ConvectionPreset = ConvectionPreset(
    "Rolls",
    "Ra 20 000 between periodic sides: two pairs of steady rolls",
    Map(
      "rayleigh" -> 20_000.0,
      "prandtl" -> 1.0,
      "sides" -> 0.0,
      "nx" -> 128.0,
      "ny" -> 32.0,
      "kick" -> 0.01
    ),
    none
  )

  val plumes: ConvectionPreset = ConvectionPreset(
    "Plumes",
    "Ra 300 000 at Pr 7 in a closed box: hot plumes rise and cold ones fall, and the flow keeps changing",
    Map(
      "rayleigh" -> 300_000.0,
      "prandtl" -> 7.0,
      "sides" -> 1.0,
      "nx" -> 144.0,
      "ny" -> 48.0,
      "kick" -> 0.02
    ),
    none
  )

  val paintedHeat: ConvectionPreset = ConvectionPreset(
    "Painted heat",
    "A quiet layer with one hot spot at the floor and one cold spot at the lid: they start the rolls",
    Map(
      "rayleigh" -> 8000.0,
      "prandtl" -> 1.0,
      "sides" -> 1.0,
      "nx" -> 96.0,
      "ny" -> 48.0,
      "kick" -> 0.0
    ),
    (nx, ny) =>
      Vector(
        heat(nx * 0.35, ny * 0.1, ny * 0.08, 0.5),
        heat(nx * 0.65, ny * 0.9, ny * 0.08, -0.5)
      )
  )

  val all: Vector[ConvectionPreset] = Vector(onset, quiet, rolls, plumes, paintedHeat)
