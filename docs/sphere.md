# The sphere

The shallow-water model ([models/shallow.md](models/shallow.md)) runs on a plane by default. With
`geometry = 1` it runs on a band of a sphere instead: a longitude–latitude grid of `nx` columns
that goes once round and `ny` rows between `latMax` south and `latMax` north. Everything else is
the same model, so the tsunami, the Kelvin wave and the Rossby wave are the real thing on a
rotating globe, with the Coriolis parameter `f = 2 Ω sin φ`, and a wave can go round the planet.
Left at `geometry = 0` nothing changes: the plane runs the same code and its digests are the same
as before (a test compares them).

## The grid

Column `i` is centred at `−180° + (i + ½)·360°/nx` and row `j` at `latMax − (j + ½)·2 latMax/ny`;
row 0 is the north. Longitude wraps; the top and bottom rows are walls, and the poles are not on
the grid (see "The edge of the band"). A cell is `dλ` wide in longitude and `dφ` high in latitude,
so on the ground it is `R cos φ · dλ` wide and `R · dφ` high: narrow towards the edges. Its
**area** is exact, `R² dλ (sin φ_north − sin φ_south)`, and so is the length of each face, `R cos φ_face · dλ`
for a horizontal face and `R dφ` for a vertical one.

`u` is the velocity to the east on the vertical faces and `v` the velocity to the south on the
horizontal faces, as on the plane; the Lab and the data files keep row 0 on top.

## The scheme

One fixed step of 50 s, forward–backward, as on the plane, with the metric of the sphere:

1. `η −= dt · div(h u, h v)` in flux form. The fluxes through the faces are multiplied by the true
   length of the face and the sum is divided by the true area of the cell, so the **volume**
   `Σ η · area` is conserved to rounding, whatever the grid does near the edge.
2. With `nonlinear = 1` the velocity is advected (semi-Lagrangian, midpoint, bilinear) in the index
   space of the grid, with the speed divided by the size of the cell where the flow is.
3. `u += dt (−g η_λ / (R cos φ) − (f + u tan φ / R) v̄ − r u)` with the **new** `η`.
4. `v += dt (−g η_φ / R + (f + ū tan φ / R) ū − r v)`, with the new `u` (`ū` is the mean of the `u` faces round the `v` face). The term
   `u tan φ / R` is the **metric term**: east and north turn as the water moves, and the linear
   model leaves it out together with the advection.
5. Poleward of `polar` degrees (see below) `η`, `u` and `v` are smoothed along the row.

The model works out the stability of the step for the cells it has: `c dt` over the narrowest
row, in the combined form `c dt √(1/dx² + 1/dy²) / √2`, must stay under 0.7, and the run is refused
otherwise with the step that would do. The sphere refuses what belongs to the plane: `edges = 2`
(there is no open sea on a globe), the sea floors 3 and 4, and the starts 2, 3 and 4.

## The edge of the band

The cells get narrow towards the poles: at 1° resolution a cell at 88° is 4 km wide, and the step
of 50 s would need a Courant number of 2.5. There are two things to do about it, and both are
used.

- **The band stops short of the poles**: `latMax` is 80° by default (10°–89°). Poleward of it
  there is nothing, and the edge is a wall.
- **The polar filter**: with `polar > 0`, on the rows poleward of `polar` degrees the height
  and the velocity are smoothed along the row after every step with the 1-2-1 filter, as many
  times as `ceil(((cos polar / cos φ)² − 1) / 2)` (the rows are then as stiff as the cells at
  `polar`). The filter keeps the volume of every row and the shut faces shut, and it lets the
  check use the cells at `polar` instead of the narrowest ones. It is off by default (0) because
  an 80° band needs none.

Measured with the independent numpy reference ([Checks](#checks)): a band to 85° at 1° resolution is stable
without the filter, a band to 88° blows up at step 6 without it and is stable with `polar = 70`
(energy 0.998 of the start after 800 steps, volume exact), and a band to 89° too.

## Starts and parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `geometry` | 0 | 0 – 1 | 0 the plane, 1 the sphere (restart only) |
| `radius` | 6.371·10⁶ | 10⁵ – 10⁸ | radius `R` in m (restart only) |
| `rotation` | 7.292·10⁻⁵ | 0 – 5·10⁻⁴ | angular speed `Ω` in rad/s, live |
| `latMax` | 80 | 10 – 89 | latitude of the edges of the band, degrees (restart only) |
| `polar` | 0 | 0 – 89 | smooth poleward of this latitude; 0 off, live |
| `watchLon`, `watchLat` | 0, 0 | ±180, ±89 | the point whose arrival time the `arrival` probe reports, live |
| `arrivalLevel` | 0.01 | 0 – 10 | m: a wave has arrived where the surface first stands this far from rest; 0 stops recording |

`dx`, `f0` and `beta` are not used on the sphere. Start 5 is **Williamson's test 2**, a steady zonal
flow `u = u₀ cos φ` with its balancing height (`amp` is `u₀` in m/s; with `depth ≈ 2997 m` and
`u₀ = 38.6 m/s` it stays as it is, and what the run shows is the error of the scheme). Start 6 is
**test 6**, the Rossby–Haurwitz wave of wave number 4 (`amp` is `ω = K` in 10⁻⁶ rad/s, 7.848 in the
test, `depth` 8000 m). The brushes and pulses are in the picture, not on the globe: a pulse of
`sigma` cells is a Gaussian in the cells, so it is narrower in metres towards the edges.

## The sea floor from a file

`--bathymetry` and the Lab's sea-floor loading read the `elevation` of a NetCDF file as before
([flooding.md](flooding.md)). If the file has regular `lat` and `lon` (or `latitude` and
`longitude`) variables in degrees, the raster knows where it lies, and on the sphere the model
resamples it by longitude and latitude: bilinearly between the cell centres, wrapping round if it
covers 360°, and holding the edge value beyond a region. A file without these coordinates is
refused on the sphere ("needs the coordinates 'lat' and 'lon'"). On the plane the coordinates are
read but not used. The digest of the file goes into the manifest as before, so a replay finds the
same sea floor.

## Probes, views and datasets

The probes `mass` and `energy` are weighted by the area of the cells. `max_height` is the highest
wave. The probe `arrival` (the last of the probes) is the time in s at which the surface first
stood `arrivalLevel` metres from rest at the watched point, or −1 before. The state has the two
slices `graticule` (1 on the meridians and parallels every 30°) and `arrival` (the same for every
cell: a map of travel times). `ShallowViews.map` draws the height on the longitude–latitude map with
the graticule over it, `ShallowViews.arrivalMap` the travel times, and `ShallowViews.globe` the
map above the plots. In the Lab the read-out under the pointer adds the longitude and latitude of the
cell (`179.5°W 79.5°N`).

A dataset of a globe has the axes `lon` and `lat` (CF: `degrees_east`, `degrees_north`, the cell
centres, `lat` decreasing as row 0 is the north) in place of `x` and `y`, and every field has the
dimensions `(time, lat, lon)`. `physis-cli verify` accepts it like any other. A **sweep over
`watchLon`** with the `arrival` probe is the table of arrival time against distance:

```bash
physis-cli sweep --model shallow --preset "Pacific tsunami" --sweep watchLon=-170,-150,-130 \
    --until 40000 --sample-every 40000
```

The column `arrival` of the CSV is the time in s (−1: the wave has not come in the time given).

## Presets and the Quake tool

| Preset | What it shows |
| --- | --- |
| Pacific tsunami | a 3 m rise off Japan on a globe 4 km deep with **no continents**; waves at 713 km/h; the `arrival` probe watches a point near Hawaii |
| Equatorial Kelvin wave | a bump on the equator: the part that goes east is trapped at the equator (a Kelvin wave, at about `√(g h)`) and runs round the globe |
| Rossby–Haurwitz wave | test 6: the pattern moves east |

The **Quake** tool sets a magnitude (6 – 9.5); the rise of the sea floor is `10^(Mw/2 − 3.5)` m
(1 m for 7, 10 m for 9) and the radius of the stroke is the size of the rupture, in cells. That is a
rule of thumb for the Lab, not a source model: a real rupture has a length, a width and a slip
that depend on the fault.

## Checks

Against known results and against `tools/shallow_sphere_reference.py`, an independent numpy
implementation (`python tools/shallow_sphere_reference.py <kelvin | zonal | rh | polar | all>`),
whose numbers the tolerances of the Scala tests are taken from:

- **Gravity wave**: a bump on the equator travels at 1.0069 of `√(g h)` in the reference (the Scala
  test allows 0.98 – 1.03); after one trip round the globe the energy is 1.0003 of its start
  and the volume 3·10⁻¹⁶.
- **Williamson 2**: the l2 error of the height after one day is 6.9·10⁻⁶ at 180 × 80 cells (the test
  allows 5·10⁻⁵), the volume drifts by 2·10⁻¹⁶, and the flow does not turn (|v| < 0.001 m/s).
- **Williamson 6**: the pattern moves east at 0.85, 0.83 and 0.82 of the barotropic theory on
  days 1, 2 and 3 (the test allows 0.76 – 0.88). The Scala model and the numpy reference agree on
  this; why it falls short of the theory is not settled here (the theory is for a non-divergent flow,
  the model has a free surface, and the grid is 2°).
- **Polar filter**: see above.

## Limits

- The band is a band: the poles are not on the grid. The polar filter is a smoothing, not a
  physical treatment, and it damps real waves near the edge.
- The grid is longitude–latitude only. A cubed sphere or an icosahedron would have no poles; this
  is not one.
- The Pacific tsunami has no continents. The model can read a real sea floor, but the repository
  contains none; the preset is a water world.
- The reference is the same scheme, independently coded, and the theory is for simple cases. There is
  no comparison with a global tsunami model or with observations, and the numbers for Hawaii are
  not forecasts.
- The step is fixed at 50 s, so the narrowest row sets the limit: finer grids than about 1° need a
  smaller `latMax` or the polar filter, and the model refuses a run it cannot keep stable instead of
  choosing a smaller step.
- The brushes and pulses of the Lab and of strokes are in the cells of the picture.
