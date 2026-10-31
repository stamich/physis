package physis.models.shallow

import physis.core.live.{ExportSpec, FieldMeta}

/**
 * How the shallow-water fields are written to a data file: metres, seconds, and `y` running
 * south, since row 0 is the northern edge.
 */
object ShallowExport:
  def spec(p: ShallowParams): ExportSpec = ExportSpec(
    "Shallow water on a rotating plane",
    Vector(
      FieldMeta(
        "height",
        "height",
        "height of the surface above rest",
        "m",
        Some("sea_surface_height_above_mean_sea_level")
      ),
      FieldMeta("speed", "speed", "speed of the water", "m s-1", Some("sea_water_speed")),
      FieldMeta("vorticity", "vorticity", "relative vorticity, positive counter-clockwise", "s-1"),
      FieldMeta(
        "depth",
        "depth",
        "depth of the water at rest",
        "m",
        Some("sea_floor_depth_below_sea_surface")
      ),
      FieldMeta("land", "land", "1 where there is land, 0 in the sea", "1"),
      FieldMeta("water", "water", "depth of the water over the floor, 0 where it is dry", "m"),
      FieldMeta(
        "dry",
        "dry",
        "1 where there is land or the shore is dry, 0 where there is water",
        "1"
      )
    ),
    dx = p.dx,
    lengthUnits = "m",
    timeUnits = "s",
    yDown = true
  )
