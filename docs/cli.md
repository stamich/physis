# Command line

In 0.5 the command line is the `RunHeadless` example (`examples`); a separate `apps/cli` is not
part of this release.

```bash
./gradlew :examples:run --args="--list"
./gradlew :examples:run --args="--model physarum --steps 600 --frames out --every 60 --scale 3"
./gradlew :examples:run --args="--model gray-scott --until 2000 --sweep F=0.02:0.06:5 --sweep k=0.05:0.07:5"
```

| Option | Meaning |
| --- | --- |
| `--model <id>` | `lorenz`, `double-pendulum`, `gray-scott`, `boids`, `wave`, `physarum` |
| `--list` | list the models with their parameters and exit |
| `--param name=value` | override a parameter (repeatable) |
| `--until <t>`, `--steps <n>` | end time, or a number of fixed steps |
| `--sample-every`, `--dt`, `--adaptive`, `--seed` | sampling, stepping and seed |
| `--manifest <path>`, `--replay <path>` | write a manifest; re-run one, with its changes and strokes |
| `--frames <dir>`, `--every`, `--scale`, `--no-color-bar`, `--compress` | numbered PNG frames (gray-scott, wave, physarum) |
| `--sweep axis` | a grid axis, see [sweeps.md](sweeps.md) (repeatable) |
| `--threads`, `--out`, `--jsonl`, `--manifests` | threads, CSV file, JSON Lines, a manifest per point |
| `--montage <png>`, `--montage-columns <n>` | a sheet of the final images of a sweep |

Without `--frames` or `--sweep` the run prints its probes as CSV. The exit code is 0 on success,
1 if a sweep point failed, and 2 for a bad option or a model that is not known.
