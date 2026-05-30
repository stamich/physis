package physis.models.grayscott

import physis.core.live.{ExportSpec, FieldMeta}

/** How the Gray–Scott fields are written to a data file: cells of size 1, time in steps. */
object GrayScottExport:
  val spec: ExportSpec = ExportSpec(
    "Gray-Scott reaction-diffusion",
    Vector(
      FieldMeta("u", "u", "concentration of the substrate U", "1"),
      FieldMeta("v", "v", "concentration of the catalyst V", "1")
    )
  )
