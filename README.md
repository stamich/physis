# Physis

[![CI](https://github.com/stamich/physis/actions/workflows/ci.yml/badge.svg)](https://github.com/stamich/physis/actions/workflows/ci.yml)

Physis (Greek *φύσις*, "nature") is a Scala 3 framework for simulating models of natural
phenomena — ODE systems, numerical fields and agent-based models — with verified numerics and
reproducible runs.

Milestone 0.1 delivered the headless simulation core and ODE support, proven on two reference
models: the Lorenz attractor and the double pendulum. Milestone 0.2 adds **Physis Lab**, a
ScalaFX desktop app that runs a model live, draws it every frame and lets you change parameters
while it runs, with sessions that replay bit for bit. Milestone 0.3 adds **fields and agents**:
2D grids with stencils and boundaries, a cell list for neighbour search, and two models on top of
them, Gray–Scott reaction–diffusion and Boids, both live in the Lab. Milestone 0.4 adds **waves**
(the damped wave equation with painted walls, a source and absorbing edges) and **interventions**:
you can paint into a running model with the mouse, and the strokes are recorded in the manifest
so that a painted session still replays bit for bit. Milestone 0.5 adds **Physarum** (agents that
lay and follow a trail, the first model where agents and a field act on each other), **parameter
sweeps** that run a grid of whole runs on all cores with the same results for any number of
threads, and **image export** with an own PNG writer: frames, montages and File → Save image. Milestone
0.5.1 adds **physis-cli**, the command line (`apps/cli`) for runs, frames, sweeps, montages and
replays with a digest check. Milestone 0.6.0 adds **fluids**: incompressible flow on a staggered
grid with a pressure projection, checked against the lid-driven cavity of Ghia et al. and the
vortex street behind a cylinder, with dye and tracer particles, painted walls and pushes, a Lab
entry, sweeps over the viscosity (a Reynolds scan), and **animated PNG** export
(`physis-cli animate`). Milestone 0.6.1 adds **File → Save animation** to the Lab: the session is
replayed in the background into an animated PNG, with an estimate, progress and Cancel, and the
same bytes as `physis-cli replay --animate`. Milestone 0.7.0 adds **convection**: a fluid heated from
below forms rolls and plumes (Rayleigh–Bénard), with temperature as a field the flow carries, the
Nusselt number as a probe, checked against the critical Rayleigh number 1708 and a numpy reference;
the Lab paints heat and cold into it and `physis-cli sweep` scans the Rayleigh number. Milestone 0.8.0 adds
**shallow water** on a rotating plane: gravity waves, geostrophic adjustment, Kelvin and Rossby waves
and a tsunami that slows down on a shelf, checked against the wave speed, the Rossby radius and the
Rossby dispersion relation and a numpy reference; the Lab paints uplifts, dips and land into it.
Milestone 0.9.0 adds **NetCDF export**: any run as a classic NetCDF file (CF metadata, fields, probes,
time, a digest per record and the manifest inside), from `physis-cli export`, **File → Export data…**
in the Lab and `physis-cli sweep --export`, with a writer and reader of the format written for
Physis and checked against `libnetcdf`, and `physis-cli verify`, which replays a file and compares it
bit for bit. Milestone 0.10.0 adds **flooding** to the shallow-water model: a sea floor that rises
above the sea, a solitary wave or N-wave that runs up a beach and floods it, a bay, run-up and
flood probes, a depth view with the shoreline, a sea floor read from a NetCDF file
(`--bathymetry`, with the file's digest in the manifest) and two Lab presets, checked against
Thacker's bowl, Synolakis' run-up law and a numpy reference.

## Modules

| Module | Contents |
| --- | --- |
| `modules/core` | `Model`, `Stepping`, `Probe`, `Run`, parameter schemas, units, seeded RNG, interventions, run manifests and replay |
| `modules/ode` | `OdeSystem`, RK4, Dormand–Prince 5(4) with step-size control, velocity Verlet for separable systems |
| `modules/verify` | convergence order, conservation, determinism and allocation checks |
| `models/lorenz` | Lorenz system, golden values from a 40-digit reference integration |
| `models/pendulum` | double pendulum with energy probes |
| `modules/netcdf` | a writer and reader of classic NetCDF (CDF-2), CF metadata, `NcExport` for runs, `NcCompare` |
| `modules/fields` | `Grid2D`, `Field2D`, boundaries, 5- and 9-point Laplacians, `ParticleSet`, `NeighborGrid` |
| `models/grayscott` | Gray–Scott reaction–diffusion with Pearson's regimes as presets |
| `models/boids` | Boids and the Vicsek model on a periodic world |
| `models/wave` | damped wave equation with walls, a source, reflective, free, periodic and absorbing edges |
| `models/physarum` | Physarum agents on a trail, with presets for networks, meshes and spots |
| `models/fluid` | incompressible flow on a staggered grid: cavity, cylinder wake, smoke, vortex pair |
| `models/convection` | Rayleigh–Bénard convection: rolls and plumes in a layer heated from below |
| `models/shallow` | shallow water on a rotating plane: tsunami, Kelvin and Rossby waves, geostrophic balance |
| `modules/view` | toolkit-free view descriptions: trajectories, plots, 2D scenes, camera, trails, colormaps |
| `modules/fx` | JavaFX renderers, render loop, frame statistics, generated parameter panel, run controls |
| `apps/lab` | Physis Lab desktop app |
| `apps/cli` | `physis-cli`: run, frames, sweep, montage, replay, list and describe, without a window |
| `examples` | `RunHeadless`: the options of 0.5 without commands, kept as an example |

Live runs (`physis.core.live`) drive a run from a simulation thread against wall-clock time and
publish snapshots through a lock-free triple buffer; parameter changes are recorded as
step-indexed events in the manifest.

## Quick start

Requirements: JDK 22 (Gradle provisions it automatically if it is missing).

```bash
./gradlew build                 # compile, unit tests, formatting check
./gradlew slowTest              # long-running validation (Lyapunov exponent, ...)

# Physis Lab: Lorenz, the double pendulum, Gray–Scott, Boids, waves, Physarum, fluids, convection and shallow water, live
./gradlew :apps:lab:run

# Lorenz attractor as CSV
./gradlew :apps:cli:run --args="run --model lorenz --until 20 --sample-every 0.05"

# Gray–Scott on 64 x 64 and Boids as CSV: a first look without a window
./gradlew :apps:cli:run --args="run --model gray-scott --preset Coral --param nx=64 --param ny=64 --until 3000 --sample-every 500"
./gradlew :apps:cli:run --args="run --model boids --param n=500 --until 10 --sample-every 1"

# A 5 x 5 patch of the Gray–Scott map as one picture, and Physarum frames
./gradlew :apps:cli:run --args="montage map.png --model gray-scott --until 8000 --param nx=128 --param ny=128 --sweep F=0.02:0.06:5 --sweep k=0.05:0.07:5"
./gradlew :apps:cli:run --args="frames out --model physarum --steps 600 --every 60 --scale 3"

# The lid-driven cavity as an animated PNG
./gradlew :apps:cli:run --args="animate cavity.png --model fluid --preset Cavity --steps 600 --every 10 --scale 3"

# Chaotic double pendulum with adaptive steps; save the manifest, then replay it
./gradlew :apps:cli:run --args="run --model double-pendulum --adaptive 1e-9 --until 10 --manifest run.json"
./gradlew :apps:cli:run --args="replay run.json"
```

`run.json` must be given as an absolute path, or relative to the `apps/cli` directory, because
Gradle runs the application from there. The replay prints the same CSV and, on standard error,
the same final state digest, and exits with 1 if the digest recorded in the manifest differs.
`./gradlew :apps:cli:installDist` builds the `physis-cli` start script in
`apps/cli/build/install/physis-cli/bin`, which can be run from any directory; see
[docs/cli.md](docs/cli.md).

In code:

```scala
import physis.core.Stepping
import physis.core.run.Physis
import physis.models.lorenz.Lorenz

val result = Physis
  .model(Lorenz.model)
  .params(_.copy(rho = 28.0))
  .stepping(Stepping.Adaptive(rtol = 1e-9, atol = 1e-9, initialDt = 1e-3, maxDt = 0.05))
  .probe(Lorenz.probes*)
  .seed(42)
  .build()
  .runUntil(20.0)

result.column("z") // Vector[Double], one value per 0.1 time units
```

See [docs/lab.md](docs/lab.md) for the Lab's controls, shortcuts and session replay.

## Verification first

Every model ships with tests that check the numerics, not only the code:

- **Invariants** — quantities the physics conserves (the pendulum's energy) must not drift.
- **Convergence order** — halving the step must reduce the error at the rate the scheme
  promises (RK4: 4, velocity Verlet: 2).
- **Reference solutions** — Lorenz at t = 1 is compared with a 40-digit integration
  (`tools/lorenz_reference.py`).
- **Known behaviour** — the largest Lyapunov exponent of Lorenz lands near 0.906.
- **Reproducibility** — a run is defined by its manifest (model version, parameters, stepping,
  seed); replaying it gives a bit-identical final state.

- **Fields** — the stencils converge at their order, diffusion decays Fourier modes at the
  analytic rate, and Gray–Scott on 64 × 64 agrees with an independent numpy implementation to
  1e-10 (`tools/gray_scott_reference.py`).
- **Waves** — the leapfrog scheme keeps the dispersion relation and conserves energy, and 64 × 64
  agrees with an independent numpy implementation to 1e-10 (`tools/wave_reference.py`); an
  absorbing edge swallows what a reflective one keeps.
- **Interventions** — a session with walls, pulses and brush strokes replays to the same digest.
- **Agents** — the cell list returns exactly the brute-force neighbours, and Boids show the
  Vicsek order–disorder transition (`tools/boids_reference.py`).

See [docs/models](docs/models) for the equations, integrators and checks of each model,
[docs/fields.md](docs/fields.md) for grids and agents, and
[docs/interventions.md](docs/interventions.md) for painting into a running model, and
[docs/datasets.md](docs/datasets.md) for NetCDF export.

## Design rules

- The engine owns the state, the clock and the step loop; models only advance a state in place.
- Inner loops do not allocate; integrators own their buffers.
- All randomness comes from a seed, keyed by step, so results never depend on threads or timing.
- Models do not depend on any UI: they describe their views as plain data (`physis-view`), and
  only `physis-fx` turns those into JavaFX. A build check fails if JavaFX reaches the core, the
  views or a model.
- The simulation thread owns the run and the FX thread owns the scene graph; they exchange only
  snapshots and commands.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0, see [LICENSE](LICENSE).
