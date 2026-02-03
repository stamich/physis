# Physis

[![CI](https://github.com/stamich/physis/actions/workflows/ci.yml/badge.svg)](https://github.com/stamich/physis/actions/workflows/ci.yml)

Physis (Greek *φύσις*, "nature") is a Scala 3 framework for simulating models of natural
phenomena — ODE systems now, numerical fields and agent-based models next — with verified
numerics and reproducible runs.

Milestone 0.1 delivered the headless simulation core and ODE support, proven on two reference
models: the Lorenz attractor and the double pendulum. Milestone 0.2 adds **Physis Lab**, a
ScalaFX desktop app that runs a model live, draws it every frame and lets you change parameters
while it runs, with sessions that replay bit for bit.

## Modules

| Module | Contents |
| --- | --- |
| `modules/core` | `Model`, `Stepping`, `Probe`, `Run`, parameter schemas, units, seeded RNG, run manifests and replay |
| `modules/ode` | `OdeSystem`, RK4, Dormand–Prince 5(4) with step-size control, velocity Verlet for separable systems |
| `modules/verify` | convergence order, conservation, determinism and allocation checks |
| `models/lorenz` | Lorenz system, golden values from a 40-digit reference integration |
| `models/pendulum` | double pendulum with energy probes |
| `modules/view` | toolkit-free view descriptions: trajectories, plots, 2D scenes, camera, trails, colormaps |
| `modules/fx` | JavaFX renderers, render loop, frame statistics, generated parameter panel, run controls |
| `apps/lab` | Physis Lab desktop app |
| `examples` | `RunHeadless`: run a model from the command line, print CSV, write and replay manifests |

Live runs (`physis.core.live`) drive a run from a simulation thread against wall-clock time and
publish snapshots through a lock-free triple buffer; parameter changes are recorded as
step-indexed events in the manifest.

## Quick start

Requirements: JDK 22 (Gradle provisions it automatically if it is missing).

```bash
./gradlew build                 # compile, unit tests, formatting check
./gradlew slowTest              # long-running validation (Lyapunov exponent, ...)

# Physis Lab: Lorenz and the double pendulum, live
./gradlew :apps:lab:run

# Lorenz attractor as CSV
./gradlew :examples:run --args="--model lorenz --until 20 --sample-every 0.05"

# Chaotic double pendulum with adaptive steps; save the manifest, then replay it
./gradlew :examples:run --args="--model double-pendulum --adaptive 1e-9 --until 10 --manifest run.json"
./gradlew :examples:run --args="--replay run.json"
```

`run.json` must be given as an absolute path, or relative to the `examples` directory, because
Gradle runs the example from there. The replay prints the same CSV and, on standard error, the
same final state digest.

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

See [docs/models](docs/models) for the equations, integrators and checks of each model.

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
