# Command line

`physis-cli` runs models without a window: it prints probes as CSV, writes frames and montages as
PNG, runs sweeps over a grid of parameters and replays manifests. It is the `apps/cli` module;
`RunHeadless` in `examples` is the same engine with the options of 0.5 and no commands.

## Starting it

```bash
./gradlew :apps:cli:installDist
apps/cli/build/install/physis-cli/bin/physis-cli --version
./gradlew :apps:cli:run --args="list"
```

The distribution needs a Java 22 or newer on the path. The examples below leave out the path.

## Commands

| Command | What it does |
| --- | --- |
| `run` | runs a model and prints its probes as CSV |
| `frames <dir>` | numbered PNGs of the first image of the view (gray-scott, wave, physarum, fluid, convection) |
| `animate <file>` | the same frames as one animated PNG (`--fps`, `--loops`) |
| `sweep` | one run per point of a grid, results in grid order, as CSV |
| `montage <png>` | a sweep that also tiles the final images of all points into one PNG |
| `replay <manifest>` | re-runs a manifest with its parameter changes and strokes |
| `list` | the models with their parameters |
| `describe <model>` | one model in full: parameters, presets, probes, step, image |

`physis-cli <command> --help` lists the options of a command. A summary with the final digest
goes to standard error, so standard output holds only the CSV or the paths of the files.

```bash
physis-cli list
physis-cli describe physarum
physis-cli run --model lorenz --until 2 --sample-every 0.5 --manifest run.json
physis-cli replay run.json
```

## Parameters and presets

`--param name=value` overrides a parameter. `--preset <name>` starts from a preset of the model
(see `describe`; gray-scott, boids, physarum, fluid and convection have them; a preset of the
fluid also paints its walls and dye, and a preset of convection its heat, and they are recorded in
the manifest), and `--param` is applied after it, so
a preset can be varied.

```bash
physis-cli run --model gray-scott --preset Coral --param nx=64 --param ny=64 --until 100 --sample-every 50
physis-cli run --model physarum --preset Mesh --param n=1000 --param nx=64 --param ny=64 --steps 20 --seed 3
```

Time is in model units. Models with a fixed step of their own (gray-scott and physarum: 1, wave:
0.5, boids: 1/60) can be run with `--steps n` instead of `--until`.

## Frames

```bash
physis-cli frames frames --model physarum --preset Mesh --param n=1000 --param nx=64 --param ny=64 --steps 20 --every 10 --scale 3
```

A picture is written before the first step and after every `--every` steps (default: a tenth of
the run) as `frame-00000.png`, `frame-00001.png`, and so on; one path a line on standard output.
`--scale n` repeats every cell n times (1 to 16), `--no-color-bar` leaves out the colour scale,
`--compress` deflates the data. A model whose view is not a field (lorenz, double-pendulum) has no
image and the command fails with exit code 1. See [export.md](export.md).

## Animations

```bash
physis-cli animate cavity.png --model fluid --preset Cavity --steps 60 --every 6 --fps 10 --scale 2
physis-cli animate loop.png --model fluid --preset Cavity --steps 30 --every 10 --loops 3
```

`animate <file>` writes the same pictures as `frames` into one animated PNG and prints its
path. `--fps n` is the number of pictures a second (1 to 65535, default 12), `--loops n` how
many times it plays (0, the default, for ever); `--every`, `--scale`, `--no-color-bar` and
`--compress` are as for frames. The first picture is also the ordinary image of the file, so a
viewer without animation shows it. The bytes depend only on the run (without `--compress`).
`replay <manifest> --animate <file>` animates a replay. The Lab's File → Save animation… writes
the same bytes as this command for the manifest it saves next to the file. See
[export.md](export.md).

## Strokes

```bash
physis-cli animate smoke.png --model fluid --preset Cavity --steps 40 --every 10 --stroke 5:brush:dye:32:32:4:set:1 --stroke 10:pulse:u:32:32:3:1 --manifest smoke.json
physis-cli replay smoke.json --animate again.png --every 10
```

`--stroke` adds an [intervention](interventions.md) at a step, and can be repeated, for `run`,
`frames`, `animate` and `replay`:

| Stroke | Meaning |
| --- | --- |
| `STEP:brush:TARGET:X:Y:R:OP:VALUE` | `OP` is `set`, `add` or `erase` (then `VALUE` may be left out); a disc of radius `R` around `(X, Y)` in cells |
| `STEP:pulse:TARGET:X:Y:SIGMA:AMPLITUDE` | a Gaussian bump |
| `STEP:disturb:X:Y:R:STRENGTH` | turns the agents in a disc (boids) |

A stroke is checked against the model before anything runs (`describe` and the model's page
list its targets); a wrong one is a usage error, exit code 2. `--manifest <path>` records the
run with its strokes, also for `frames`, `animate` and `replay`, so that the session replays
bit for bit. A replay with more strokes records them too and does not compare the digest, since
the run is a different one now.

## Sweeps

```bash
physis-cli sweep --model gray-scott --param nx=32 --param ny=32 --sweep F=0.03,0.05 --sweep k=0.06,0.065 --until 20 --out map.csv --manifests points
physis-cli sweep --model gray-scott --param nx=32 --param ny=32 --sweep F=0.02:0.06:5 --sweep k=0.05:0.07:5 --dry-run
```

Axes are written as in [sweeps.md](sweeps.md): `name=a:b:n`, `name=log:a:b:n`, `name=a,b,c` and
`seed=a..b`. The CSV has one row per point, first axis slowest, with the same rows for any number
of `--threads`. Further options:

| Option | Meaning |
| --- | --- |
| `--out <path>` | write the CSV to a file instead of standard output |
| `--jsonl <path>` | also write one JSON object per point |
| `--manifests <dir>` | also write a manifest per point; each carries its digest and replays to it |
| `--dry-run` | print the axes and the number of points, run and write nothing |
| `--fail-fast` | check every point against the schema and the model's limits first and run nothing if one is refused |
| `--quiet` | no `# done/total` lines on standard error |

Without `--fail-fast` a point the model refuses is reported at its point (`ok` is false, the
reason is in `error`), the other points run, and the exit code is 1.

## Montage

```bash
physis-cli montage map.png --model gray-scott --param nx=32 --param ny=32 --sweep F=0.03,0.05 --sweep k=0.06,0.065 --until 20
```

The final images of all points are tiled into one PNG with a gap of one pixel, the last axis
along a row (`--montage-columns n` for another width, `--scale` and `--compress` as for frames).
A point that failed leaves its tile empty.

A preset that paints (the fluid's) is applied to every point, so a sweep over `viscosity` with
`--preset "Cylinder wake"` is a Reynolds scan; sweeping `nx` or `ny` with such a preset is
refused, because the painting is for one size. See [sweeps.md](sweeps.md).

## Convection

```bash
physis-cli sweep --model convection --param nx=32 --param ny=16 --sweep rayleigh=1200,3000 --until 800 --sample-every 10 --out nu.csv
physis-cli animate rolls.png --model convection --preset Rolls --param nx=64 --param ny=16 --steps 400 --every 100
```

The first command scans the Rayleigh number: at 1200 the `nusselt` column of the CSV stays at 1
(heat only conducts) and at 3000 it rises well above 1 (the layer has formed rolls). The results are
the same on any number of threads. The second animates a layer between periodic sides; a preset of
convection paints its heat as ordinary strokes, and the manifest replays to the same digest and the
same animation. The model and its checks are described in [models/convection.md](models/convection.md).

## Replay

```bash
physis-cli replay run.json
```

A replay applies the recorded parameter changes and strokes at the recorded steps and prints the
same CSV as the original run. If the manifest records a digest and the replay ends at another,
the command prints `# digest DIFFERS` with both digests and exits with 1; when they match it
prints nothing extra. `replay <manifest> --frames <dir>` writes frames of the replay and checks
the digest too. Manifests written by `run --manifest`, by a sweep or by the Lab (File → Save
manifest) can be replayed; the model and its version must be the ones the manifest names.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | done |
| 1 | the run failed: an unknown model or preset, a parameter outside its range, a sweep point that failed, an unreadable manifest, a replay with another digest |
| 2 | the command line is wrong: an unknown command or option, an option that does not belong to the command, a missing value or main argument, a number that is not a number |
