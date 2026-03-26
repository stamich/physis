# Sweeps

A sweep runs a model once for every point of a grid of parameters and reports one row per point.
It lives in `physis.core.sweep` and is used by `RunHeadless --sweep` (see [cli.md](cli.md)).

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
such as a final field or an image. A sweep with 4 threads is checked to be at least 1.8 times
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

## Example

```bash
./gradlew :examples:run --args="--model gray-scott --until 8000 \
  --param nx=128 --param ny=128 --param noise=0 \
  --sweep F=0.02:0.06:5 --sweep k=0.05:0.07:5 --out map.csv --montage map.png"
```

A slow test (`MapSuite`) classifies this same 5 × 5 patch of Pearson's map (extinct, a few spots,
pattern, filled) and requires that at least 20 of 25 points are in the class the independent
numpy reference gives (`tools/gray_scott_reference.py map`).
