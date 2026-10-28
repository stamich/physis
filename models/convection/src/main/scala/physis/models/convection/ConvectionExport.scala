package physis.models.convection

import physis.core.live.{ExportSpec, FieldMeta}

/**
 * How the convection fields are written to a data file: lengths in layer depths per cell
 * (`ny` cells make one depth), temperature in units of the difference between floor and lid.
 */
object ConvectionExport:
  def spec(p: ConvectionParams): ExportSpec = ExportSpec(
    "Rayleigh-Benard convection",
    Vector(
      FieldMeta("temperature", "temperature", "temperature, 1 at the floor and 0 at the lid", "1"),
      FieldMeta(
        "deviation",
        "deviation",
        "temperature minus the conduction profile",
        "1"
      ),
      FieldMeta("vorticity", "vorticity", "curl of the velocity", "1"),
      FieldMeta("speed", "speed", "size of the velocity", "1"),
      FieldMeta("wall", "wall", "1 where there is a wall, 0 in the fluid", "1")
    ),
    dx = 1.0 / p.ny
  )
