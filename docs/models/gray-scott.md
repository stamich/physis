# Gray–Scott reaction–diffusion

Module: `models/grayscott` · id `gray-scott` · version 0.3.0

## Equations

Pearson (1993), two concentrations u and v on a periodic 2D grid with unit cell size:

```
du/dt = Du ∇²u − u v² + F (1 − u)
dv/dt = Dv ∇²v + u v² − (F + k) v
```

| Parameter | Default | Range | Meaning |
| --- | --- | --- | --- |
| `Du` | 1.0 | 0 – 1.25 | diffusion rate of u |
| `Dv` | 0.5 | 0 – 1.25 | diffusion rate of v |
| `F` | 0.0367 | 0 – 0.1 | feed rate |
| `k` | 0.0649 | 0 – 0.1 | kill rate |
| `nx`, `ny` | 256 | 32 – 1024 | grid columns and rows (**restart only**) |
| `noise` | 0.01 | 0 – 0.1 | perturbation of the initial seed (**restart only**) |

Initial state: u = 1, v = 0 everywhere, except a centred square (a tenth of the smaller grid
side, at least 2 cells) with u = 0.5, v = 0.25, each value perturbed by up to `noise / 2` from the
run's seeded stream. The same seed gives the same pattern on every machine.

## Integration

Forward Euler with `dt = 1` (`GrayScott.stepping`; time is measured in steps) and the isotropic
9-point Laplacian from [fields](../fields.md). The diffusion terms are stable only while
`dt · max(Du, Dv) ≤ 1.25`; the model's `check` refuses larger values when a run is built and when
a parameter is changed live, and the Lab shows the message instead of crashing the run.

## Presets

Named regimes of the (F, k) plane, applied as one parameter change:

| Preset | F | k | Pattern |
| --- | --- | --- | --- |
| Mitosis | 0.0367 | 0.0649 | spots that grow and divide |
| Coral | 0.0545 | 0.0620 | coral-like growth |
| Maze | 0.0290 | 0.0570 | labyrinth of stripes |
| Solitons | 0.0300 | 0.0620 | pulsating solitons |
| Worms | 0.0540 | 0.0629 | thick worms |

Each preset was run against the independent reference (`tools/gray_scott_reference.py regimes`)
and grows a pattern from the seed. The first Worms values from the plan (F 0.078, k 0.061) died
out in the reference, so the preset uses the values above.

## Probes and views

Probes: `u_mean`, `v_mean`, `v_max` and `coverage` (the fraction of cells with v > 0.2). The Lab
shows u and v as heatmaps side by side (fixed range 0 to 1, cividis for u, viridis for v) above a
plot of mean v, max v and coverage over the last 5 000 steps. Pointing at a cell shows its value
at the bottom of the window.

Speed in the Lab is in steps per second: 10 to 4 000, 600 by default.

## Painting

The Lab's **Paint v** tool sets v inside a disc to a value (0 to 1; the right button erases),
and **Erase v** sets it to zero; both are `Brush` interventions on the `v` slice, periodic across
the border. Values are clamped to [0, 1] when painted. A bump of v in the rest state `u = 1`,
`v = 0` grows into a new pattern. See [interventions](../interventions.md).

## Verification

| Check | Threshold | Where |
| --- | --- | --- |
| u = 1, v = 0 is a fixed point | bit for bit | `GrayScottSuite` |
| Pure diffusion multiplies a Fourier mode by `1 + dt D λ` per step | 1e-12 relative | `GrayScottSuite`, `DiffusionSuite` |
| Pure diffusion conserves the totals of u and v | 1e-12 relative | `GrayScottSuite` |
| 64 × 64 grid, 100 steps vs the numpy reference (`tools/gray_scott_reference.py`) | 1e-10 per cell | `ReferenceSuite` |
| Digest after 1 000 steps vs the reference's digest | equal | `ReferenceSuite` |
| Stability guard accepts D·dt up to 1.25, refuses more, at build and live | exact | `GrayScottSuite` |
| Seeded run with noise: golden digest after 500 steps | equal | `SeededGoldenSuite` |
| Live session with three changes and a reseed replays | same digest | `SeededGoldenSuite`, `LabManifestsSuite` |
| A brush sets and erases cells, wraps across the seam and keeps u and v in [0, 1] | exact | `GrayScottBrushSuite` |
| Regimes on 128 × 128 (mitosis coverage, the pattern dying out at F 0.09, k 0.06) | thresholds from the reference | `RegimeSuite` (slow) |
| A step allocates less than one byte | | `GrayScottSuite` |
| At least 15 million cell updates per second on 256 × 256 | | `ThroughputSuite` (slow) |

The regime tests run without noise, as the reference does: noise makes the Java model develop
patterns faster than the reference, so thresholds measured with noise would not be independent.
