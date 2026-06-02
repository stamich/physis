# Sweeps

A sweep runs a model once for every point of a grid of parameters and reports one row per point.
It lives in `physis.core.sweep` and is used by `physis-cli sweep` and `physis-cli montage` (see [cli.md](cli.md)). `--export <dir>` also writes a NetCDF dataset per point, `point-0000.nc` and so on, each the replay of the manifest of its point (see [datasets.md](datasets.md)).

## Grid

`ParamGrid.parse(specs, schema)` reads axes of these forms:

| Axis | Meaning |
| --- | --- |
| `F=0.02:0.06:5` | 5 values from 0.02 to 0.06, evenly spaced |
| `Dv=log:0.01:1:4` | 4 values from 0.01 to 1, evenly spaced in the logarithm |
| `k=0.05,0.06,0.07` | the listed values |
| `seed=1..10` | seeds 1 to 10 (a seed axis sets the run's seed) |

The grid is the product of the axes, the **first axis slowest**. A name that the model does not
have, a value outside its range or an empty axis is an error before any run starts.

## Running

`Sweep.run(setup, grid, threads)(extract)` runs each point as a whole separate run on a fixed
pool of daemon threads. Runs share nothing, so the answer does not depend on the number of
threads or on which run finishes first: **results come back in grid order, with the same digests
for 1 or 16 threads**. A point that fails (a parameter the model refuses) is reported at its
point and does not stop the others. `extract` reads more from a finished run on its own thread,
such as a final field or an image. A sweep with 4 threads is checked to be at least 1.5 times
faster than with 1 (slow test).

## Output

`SweepOutput.csv` writes one row per point: the index, one column per axis, then `ok`, `time`,
`steps`, `rejected`, `digest`, the last value of every probe and an `error` column. `jsonLines` writes the same as one JSON
object per line, and `writeManifests` writes `point-NNNN.json`, a [manifest](interventions.md)
for each point that replays with `--replay` to the same digest. A failed point has `ok` false and its message in `error`.

## Montage

`Montage.tile` joins the final images of the points into one PNG sheet, one tile per point with
a gap between them, the last axis along a row. A failed point leaves its tile empty. See
[export.md](export.md).

## Example: a Reynolds scan

A preset that paints (the fluid's cylinder) is part of every point, so the viscosity axis is a
Reynolds axis (`Re = 1.0 · D / ν` with the diameter `D` = 14.4 cells of the 192 × 72 preset):

```bash
physis-cli montage re.png --model fluid --preset "Cylinder wake" --param tracers=0 \
  --sweep viscosity=0.384,0.192,0.096 --until 200 --montage-columns 3
```

The three tiles show the wake at Reynolds numbers 37.5, 75 and 150, the first below the critical
number of about 47, where the wake stays steady. The street needs a few hundred time units to
develop (the slow test runs 750), so a short scan shows the start-up. The CSV and the montage are the same for any number of threads, and the manifest of each
point (`--manifests`) holds the cylinder as a stroke at step 0. Sweeping `nx` or `ny` with such
a preset is refused.

## Example: a Rayleigh scan

The convection model has the Rayleigh number as a parameter, so a sweep over it gives the
Nusselt number against the heating. Below the critical value (about 1708) the layer only
conducts and `nusselt` stays at 1; above it the rolls carry heat:

```bash
physis-cli sweep --model convection --param nx=48 --param ny=24 --sweep rayleigh=1200,3000,5000 --until 1500 --sample-every 10
```

At 48 × 24 with Prandtl number 1 the settled values are about 1.0, 1.6 and 2.1 (numpy reference: 1.585 and 2.058). A short run
shows the start-up: the rolls need a diffusion time (`H²/κ`, 720 time units here) to grow, longer
the nearer the critical value. See [models/convection.md](models/convection.md).

## Example: beta and gravity

The shallow-water model has the beta effect and gravity as parameters, so a sweep gives the
drift of a Rossby wave without and with `β`, or the speed of the waves for two values of `g`:

```bash
physis-cli sweep --model shallow --param start=2 --param amp=0.1 --param depth=500 --param dx=50000 --param edges=0 --sweep beta=0,4e-11 --until 20000 --sample-every 1000
physis-cli sweep --model shallow --param start=1 --sweep gravity=5,10 --until 3000 --sample-every 1000
```

A point whose parameters break the stability rule (a Courant number over 0.7) fails with the
reason, and the other points still run. See [models/shallow.md](models/shallow.md).

## Example

```bash
physis-cli montage map.png --model gray-scott --until 8000 \
  --param nx=128 --param ny=128 --param noise=0 \
  --sweep F=0.02:0.06:5 --sweep k=0.05:0.07:5 --out map.csv
```

A slow test (`MapSuite`) classifies this same 5 × 5 patch of Pearson's map (extinct, a few spots,
pattern, filled) and requires that at least 20 of 25 points are in the class the independent
numpy reference gives (`tools/gray_scott_reference.py map`).
