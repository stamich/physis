# Shallow water

Module: `models/shallow` · id `shallow` · version 0.8.0

A thin layer of water on a rotating plane, in two dimensions: the sea in which a tsunami runs, a
tide sloshes, a Kelvin wave follows a coast and a Rossby wave drifts west. The water is described
by the height of its surface `η` above rest and by the horizontal velocity `(u, v)`, both
independent of depth. With `h` the depth of the water at rest, `g` gravity and `f` the Coriolis
parameter:

    η_t + ∇·((h + η) u) = 0
    u_t + (u·∇)u = −g η_x − f v − r u
    v_t + (u·∇)v = −g η_y + f u − r v

`r` is the bottom friction. In the **linear** model the products `η u` and `(u·∇)u` are left out
and the depth is the depth at rest. Waves then run at `c = √(g h)`: 198 m/s, or 713 km/h, in 4 km
of water. Rotation changes this in two ways. A disturbance does not run downhill; it settles into
a current along the front, within the **Rossby radius** `c / f`, where the pressure force and the
Coriolis force balance (**geostrophic balance**). And since `f` grows towards the pole,
`f = f₀ + β y` (the **beta plane**), balanced waves, **Rossby waves**, drift west.

## Orientation

North is at the top of every picture: row 0 is the northern edge, and `y` and `v` run south, which is how the equations above are written. So
for `f > 0` (the northern hemisphere) a hump of water swirls clockwise as seen from above, a Kelvin
wave runs south along a west coast (the coast on its right hand) and the Rossby drift is towards
column 0. `f` at row `j` is `f₀ + β (ny/2 − j) dx`; `β` makes it larger towards the top.

## Scheme

The grid is the staggered grid of the fluid ([numerics.md](../numerics.md)): `η` at the cell
centres, `u` on the vertical faces, `v` on the horizontal faces. The faces next to land and on the
border of a closed domain carry no flow. One step is a fixed `dt = 50 s`:

1. `η −= dt · ∇·(open · h · u)`, in flux form, so that the volume of the sea (the `mass` probe)
   stays constant to rounding as long as nothing is painted;
2. `u += dt (−g η_x − f v̄ − r u)` with the **new** `η` (`v̄` is the mean of the four `v` faces round the `u` face);
3. `v += dt (−g η_y + f ū − r v)` with the **new** `u`.

This is the forward–backward scheme: stable for the gravity waves up to a Courant number
`c dt / dx < 1/√2` (the model refuses more than 0.7), neutral for them, and second order in space
on the C grid. `f dt` and `r dt` must stay under 0.5; the model checks all three before the first
step (`Shallow.check`) and the schema ranges are checked on every change of a parameter.

With `nonlinear = 1` the depth is `h + η` (at least 0; without `flooding` the model does not flood dry land, see [flooding.md](../flooding.md)) and the
velocity is also advected, with the semi-Lagrangian step of the fluid on the double-buffered
`u` and `v`. Waves then steepen and slow in shallow water. A step allocates nothing.

## Parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `gravity` | 9.81 | 0.1 – 30 | m/s² |
| `depth` | 4000 | 1 – 7000 | depth at rest in m, live; the seabed shape scales it |
| `dx` | 20 000 | 500 – 100 000 | cell size in m (restart only) |
| `f0` | 10⁻⁴ | −5·10⁻⁴ – 5·10⁻⁴ | Coriolis parameter at the middle row, 1/s |
| `beta` | 0 | 0 – 10⁻⁹ | change of `f` with `y`, 1/(m s) |
| `friction` | 0 | 0 – 10⁻³ | bottom friction `r`, 1/s |
| `nonlinear` | 0 | 0 – 1 | 0 linear, 1 with advection and the full depth |
| `edges` | 1 | 0 – 2 | border (restart only): 0 channel (periodic left and right, walls above and below), 1 closed basin, 2 open sea |
| `sponge` | 10 | 0 – 64 | width in cells of the absorbing border of the open sea (restart only) |
| `seabed` | 0 | 0 – 4 | shape of the sea floor (restart only): 0 flat, 1 shelf, 2 seamount, 3 plane beach, 4 beach with a bay (3 and 4 need `flooding`) |
| `start` | 0 | 0 – 4 | initial state (restart only): 0 rest, 1 a front, 2 a Rossby wave, 3 a solitary wave, 4 an N-wave |
| `amp` | 0.2 | 0 – 20 | amplitude in m of the front or the wave |
| `nx` | 128 | 16 – 512 | grid columns (restart only) |
| `ny` | 96 | 16 – 512 | grid rows (restart only) |
| `flooding` | 0 | 0 – 1 | 1 floods and dries the shore (restart only; needs `nonlinear`) |
| `dryDepth` | 0.01 | 10⁻⁶ – 10 | water in m below which a face is dry |
| `slope` | 0.05 | 10⁻³ – 1 | rise of a plane beach (restart only) |

The **open sea** absorbs: within `sponge` cells of the border `η`, `u` and `v` are pulled to zero
by up to 10 % a step at the border, with a quadratic ramp, so that waves leave without coming back. About
1 in 1250 of the energy of a pulse stays in the sea (8·10⁻⁴ in the reference); a closed basin keeps
it. The **shelf** rises from the full depth at 55 % of the width to 5 % of it at 90 %; the
**seamount** is a Gaussian hill that takes 80 % of the depth in the middle.

## Presets

| Preset | Sea | What it shows |
| --- | --- | --- |
| Tsunami | open, 4 km deep, shelf, nonlinear, 160 × 96 at 20 km | a 2 m rise of the sea floor, 1000 km long, sends waves at 713 km/h; they slow down on the shelf and grow |
| Adjustment | channel, 200 m, 96 × 128 at 10 km | a step in the surface does not run down but settles into a current along the front, `R = c / f` = 44 km wide |
| Rossby wave | channel, 500 m, β = 4·10⁻¹¹, 96 × 48 at 50 km | a balanced wave whose pattern drifts west with the water moving along the lines of equal height |
| Kelvin wave | closed basin, 200 m, 96 × 200 at 10 km | a bump at the west coast sends a wave south along it, trapped within `R` of the coast |
| Harbour | open, 40 m, nonlinear, 120 × 80 at 2 km | a wave front meets a breakwater with a gap and an island: diffraction |
| Beach run-up | open, 100 m, flooding, 120 × 40 at 2.5 km | a 6 m solitary wave runs up a plane beach and back |
| Harbour flood | open, 100 m, flooding, 120 × 60 at 2.5 km | an 8 m solitary wave comes ashore at a bay |

A preset sets parameters and, for Tsunami, Kelvin wave and Harbour, paints at step 0 as ordinary
interventions, so it is part of the manifest. Choosing one in the Lab starts a new run.

## Probes and views

Probes, in this order: `mass` (volume above rest, m³), `energy` (m⁵/s², kinetic plus potential
per unit of area, summed), `max_height`, `min_height`, `max_speed`, and with flooding
`wet_fraction`, `flooded_area`, `runup`, `max_runup`, `max_inundation` ([flooding.md](../flooding.md)).

Slices of the state: `height`, `speed`, `vorticity` (positive counter-clockwise), `depth` (at rest,
with the seabed), `land`, and `water` (depth of the water now) and `dry`. The Lab shows the height, a diverging map centred at zero, above
plots of the energy and the highest surface over the last 200 000 s; `ShallowViews` also has the
speed, the vorticity and the depth.

## Interventions

| Target | Brush | Pulse |
| --- | --- | --- |
| `height` | set, add or erase the height in a disc | a Gaussian bump, negative values dip |
| `land` | paint or erase land | not accepted |

Land closes the faces around it; the cells it covers lose their water and their flow (the `mass`
probe changes), and cells that are erased come back at rest at height 0. In the Lab the tools are **Uplift**
(the right button lowers), **Drop** (the other way round), **Land** (the right button erases) and
**Erase land** and **Water level** (a flat layer of metres in a disc, the right button takes it away).

## Checks

Against theory and against `tools/shallow_reference.py`, an independent numpy implementation of
the **linear** scheme (`python tools/shallow_reference.py <seiche | front | adjustment | rossby |
kelvin | shoaling | sponge | energy | all>`); the Scala tests use the numbers it prints:

- **Seiche** in a closed basin 2000 km long, 4 km deep: the period is 1.0004 times `2L/c`; halving
  `dx` and `dt` four times the observed order of convergence is 2.00.
- **Wave speed**: the crest of a pulse moves at 0.989 of `c`; the crest of a tsunami reaches
  its place at 15 300 s against 15 145 s expected.
- **Geostrophic adjustment**: the fitted width is 44.6 cells against the Rossby radius of 44.3,
  the height at the front is `A/2` (0.1000), and the time-mean current is within 1.4 % of the
  geostrophic one `g η_y / f`.
- **Kelvin wave**: 44.05 m/s against 44.29; north of the pulse only 0.23 of the southward height
  goes (0.46 in the preset). With the sign of `f` reversed the wave goes the other way.
- **Rossby wave**: the pattern's phase after 8000 and 12 000 steps is 1.004 and 0.992 of the
  dispersion relation `ω = −β k / (k² + l² + f₀²/c²)`; without `β` it stays (2·10⁻⁴).
- **Shoaling**: the wave grows by 1.311 over a slope where Green's law gives 1.326.
- **Energy** in a closed basin keeps within 0.978 and 1.049 of its start (the discrete energy is
  not conserved exactly by this scheme); the volume is conserved to rounding.

The preset tests compare the Rossby wave's phase at 2000 and 4000 steps (0.987 and 1.027 of the
dispersion relation, tolerance 10 %) and the Kelvin wave's southward peak (0.045 at step 271)
with the numpy output.

## Datasets

- A run can be written as a NetCDF file with `height`, `speed`, `vorticity`, `depth`, `land`, `water` and `dry` (`physis-cli export`, File → Export data…), in metres and seconds with `y` running south; see [datasets.md](../datasets.md).

## Limits

- The reference is linear: the nonlinear mode is tested for conservation, the steepening and
  the right limit at small amplitude, not against another implementation.
- Without `flooding = 1` there is no flooding: a cell whose depth would be negative is held at
  zero depth. With it the sea floods and dries; the run-up is damped by the advection
  (0.77 of Synolakis' law for H/d = 0.0185), see [flooding.md](../flooding.md).
- The step is fixed at 50 s, so a cell cannot be smaller than `c dt / 0.7` (14.2 km for 4 km of
  water). Finer cells need shallower water or a smaller `g`; the model refuses the run otherwise.
- Spherical geometry, tides and real coastlines belong to later milestones.
