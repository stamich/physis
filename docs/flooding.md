# Flooding

Module: `models/shallow` · since 0.10.0 · `flooding = 1`

With `flooding = 1` the shallow-water model lets the sea come ashore. A beach is a sea floor that
rises above the level of the sea at rest; a wave that reaches it wets cells that were dry, runs
up the slope, stops, and runs back. Without it (the default) a coast is a wall, as before, and
every earlier run has the same digest.

## What changes

The variable of the model is still the height of the surface `η`, measured from the level of the
sea at rest. The floor (the *bed*) `z = −depth · shape` is now a height too: negative under the
sea, positive on the shore. A cell holds water `max(0, η − z)` deep.

- A **dry cell** keeps `η = z`. A lake at rest therefore has no slope to push the water anywhere:
  it stays at rest *exactly*, on a flat floor, on a beach, round a bay.
- The depth of the water over a **face** is the mean of what stands over the higher of the two
  neighbouring floors (the *sill*). A face carries flow when either side has more than `dryDepth`
  of water over the sill; the flow through a dry face is set to zero.
- The continuity equation is in flux form and **limited**: no cell gives away more water in a
  step than it holds, so the depth never goes below zero and the volume is exact to rounding. The
  limit is applied to the outflow only, so it costs nothing where the sea is deep.
- The **volume** (the `mass` probe) is `Σ (η − max(0, z)) dx²`: the water above the level of
  the sea at rest. It is constant unless something is painted or absorbed, with or without a
  shore.

It needs `nonlinear = 1` (the depth must follow the surface); `Shallow.check` refuses the other
combination. The model checks the same Courant number as before, with the depth at rest.

## Parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `flooding` | 0 | 0 – 1 | 1 lets the water come ashore (restart only) |
| `dryDepth` | 0.01 | 10⁻⁶ – 10 | m of water below which a face is dry, live |
| `slope` | 0.05 | 10⁻³ – 1 | rise of a plane beach, per metre of run (restart only) |

Two sea floors and two starts were added with it: `seabed = 3` is a **plane beach** (flat from
the left up to half the width, then rising with `slope`, so that the shore is where the floor
reaches the level of the sea, and land beyond it), `seabed = 4` the same beach with a **bay**
cut into it (a Gaussian trough along the shore, 0.3 of the depth deep and a tenth of the height
wide). `start = 3` is a **solitary wave** of height `amp`, `η = amp sech²((x − x₀)/w)` with
`w = max(√(4 depth³ / (3 amp)), 5 dx)` and the velocity of the progressive wave; `start = 4` is
an **N-wave**, a hump with a dip three widths ahead of it. Both start a quarter of the way along
the sea and move toward the beach.

## Probes and views

Five probes were added after the five of the model (so `ProbeAt(1)` and `ProbeAt(2)` of the
views still mean energy and highest surface): `wet_fraction` (the share of the sea and shore
cells with water), `flooded_area` (m² of shore under water), `runup` (the highest surface over the
shore now, m above the sea at rest) and `max_runup` and `max_inundation` (the highest run-up and
the deepest water over the shore that the run has seen). Without flooding all five read 0; the
two maxima are kept by the run, are not part of the digest, and start at 0.

The state has two more slices, `water` (the depth of the water, 0 where it is dry) and `dry` (1
for land and dry shore). `ShallowViews.water` draws the depth and greys the dry cells, so the
edge of the grey is the shoreline; `ShallowViews.flooding` puts it above the plots. Datasets
carry `water` and `dry` as fields.

## Sea floor from a file

`--bathymetry coast.nc` (CLI, `run`, `frames`, `animate`, `export`, `sweep`) sets the floor of a
shallow run with `flooding = 1` from a NetCDF file. The file needs a variable `elevation` (metres,
negative under the sea) on two dimensions, rows then columns; if the file has a `lat` variable
that increases, the rows are turned over so that row 0 is the north. The raster is stretched
over the whole grid and sampled bilinearly. `depth` stays the reference depth for the wave speed
and for the time step, so choose it near the typical depth of the file.

The file is not copied into the manifest: the manifest holds an intervention `load` with the
file name and the first 8 bytes of the SHA-256 of the file. A replay (and `verify`) reads the file
again and refuses it if the digest differs. A manifest is only as portable as the file's path.

## Presets and tools

Two Lab presets: **Beach run-up** (a 6 m solitary wave over a plane beach, a sea 100 m deep, 120
× 40 cells of 2.5 km, so 0.63 Courant) and **Harbour flood** (an 8 m wave at a bay). The **Water
level** tool adds a flat layer of water to a disc (the right button takes it away); the surface
is never dug below the floor. `physis-cli sweep --preset "Beach run-up" --sweep amp=4,8,12` is a
table of run-up.

## Differences and limits

- The scheme is the model's: the velocity is advected semi-Lagrangian, which damps the swash.
  The run-up of a solitary wave on a 1:19.85 beach is a fraction of Synolakis' law, **0.77 for
  H/d = 0.0185 and 0.94 for H/d = 0.01** at 0.25 d resolution, and closer to it as the grid is
  refined (0.74 at 0.5 d, 0.81 at 0.125 d). The tests compare the Scala run with the independent
  numpy scheme (`tools/shallow_flood_reference.py`), which has the same damping, to 3 %, and with
  the law to the band above, not to the law itself.
- Thacker's oscillating bowl (a parabolic basin, an exact solution with a moving shoreline) is
  followed with an L1 error of 4.5 % at 0.1 a and 1.9 % at 0.05 a; the shoreline is a few cells
  out and the volume is exact.
- Flooding applies to the nonlinear equations on the plane grid; the spherical grid is left to a
  later milestone. Land painted with the **Land** tool is still a wall.
- The bathymetry of a file sits on the whole grid; there is no map projection.
