package physis.models.physarum

import physis.core.live.{ExportSpec, FieldMeta}

/** How the Physarum fields are written to a data file; the agents are not fields. */
object PhysarumExport:
  val spec: ExportSpec = ExportSpec(
    "Physarum agents and their trail",
    Vector(
      FieldMeta("trail", "trail", "chemical trail left by the agents", "1"),
      FieldMeta("food", "food", "food sources", "1")
    )
  )
