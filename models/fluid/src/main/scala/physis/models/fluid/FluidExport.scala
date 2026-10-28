package physis.models.fluid

import physis.core.live.{ExportSpec, FieldMeta}

/** How the fluid fields are written to a data file: cells of size 1, time in the model's units. */
object FluidExport:
  val spec: ExportSpec = ExportSpec(
    "Incompressible flow",
    Vector(
      FieldMeta("dye", "dye", "concentration of dye carried by the flow", "1"),
      FieldMeta("vorticity", "vorticity", "curl of the velocity", "1"),
      FieldMeta("speed", "speed", "size of the velocity", "1"),
      FieldMeta("wall", "wall", "1 where there is a wall, 0 in the fluid", "1")
    )
  )
