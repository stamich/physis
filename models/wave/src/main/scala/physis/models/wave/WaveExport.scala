package physis.models.wave

import physis.core.live.{ExportSpec, FieldMeta}

/** How the wave field is written to a data file: cells of size 1, time in the model's units. */
object WaveExport:
  val spec: ExportSpec = ExportSpec(
    "Damped wave equation",
    Vector(
      FieldMeta("u", "u", "displacement of the surface", "1"),
      FieldMeta("wall", "wall", "1 where there is a wall, 0 in the water", "1")
    )
  )
