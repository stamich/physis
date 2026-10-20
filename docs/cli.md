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
| `frames <dir>` | numbered PNGs of the first image of the view (gray-scott, wave, physarum) |
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
(see `describe`; gray-scott, boids and physarum have them), and `--param` is applied after it, so
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
