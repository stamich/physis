# Changelog

All notable changes to Physis are listed here. Versions follow `vX.Y.Z` tags on `main`.

## 0.2.0 — physis-fx and Physis Lab

Second milestone: a window on the 0.1 engine. Physis Lab runs a model live, draws it every frame
and changes parameters while it runs; live sessions replay bit for bit.

### Added

- **Build**: JavaFX 24.0.2 and ScalaFX 24.0.2-R36 as plain Maven artifacts with the platform
  classifier, through a `physis.javafx-conventions` plugin used only by `:fx` and `:apps:lab`;
  a `checkUiFree` check that fails if JavaFX or ScalaFX reaches the runtime classpath of the
  core, the views or a model. CI runs FX smoke tests under xvfb on Linux and the slow tests on
  every pull request.
- **Parameter events** (`modules/core`): `Run.setParams` validates changes through the schema
  and applies them before the next step, recording `ParamEvent`s; `Run.step` and `runSteps`
  take whole steps; `RunBuilder.events` schedules recorded changes for replay. `ParamKind`
  (real, integer, flag) per schema field.
- **Manifest format 2**: `format`, `events`, `steps` and `digest`; format-1 manifests still read.
- **Live runs** (`physis.core.live`): `LiveRun` with a simulation thread, commands (pause,
  resume, single step, reset, speed, parameters), wall-clock pacing in whole steps with a frame
  budget, step-rate measurement and `manifest()` for replayable sessions; `Snapshot`,
  `StateExport` and a lock-free single-producer/single-consumer `TripleBuffer`.
- **physis-view** (`modules/view`, no JavaFX): `Channel`, `View` (trajectory, plot, 2D scene,
  layout), `Shape`, `Bounds`, `Rgb`, `SceneInput`; orbit `Camera`; `Trail` ring buffer;
  smoothly decaying `ViewBounds`; viridis, cividis, diverging and cyclic `Colormap`s.
- **physis-fx** (`modules/fx`): `RenderLoop` on AnimationTimer, `FrameStats` and
  `StatsOverlay`; trajectory renderer with 32 colour bands, 2D and rotatable 3D; scrolling plot
  with axes; 2D scene renderer with trails; `ParamPanel` generated from the schema through a
  toolkit-free `ControlSpec`; `RunControls`.
- **Model views**: `LorenzViews` (3D attractor, coordinate plot) and `PendulumViews`
  (mechanism with outer-bob trail, energy drift).
- **Physis Lab** (`apps/lab`): model picker, parameter panel, run controls, views, statistics,
  keyboard shortcuts, model switching, manifest save and replay with a digest check.
- **Documentation**: [docs/lab.md](docs/lab.md), view sections on the model pages, README.

### Changed from the 0.2 plan

- Live runs advance in whole steps (`Run.step`) instead of `advanceTo` with wall-clock
  targets: landing on timing-dependent targets would change step sizes and break replay. The
  manifest records the step count, and replay takes the same steps.
- Pausing, stepping and speed are commands handled between steps; the snapshot carries speed,
  pause state and the last parameter error, so the UI never queries the run directly.

## 0.1.0 — Core + ODE

First milestone: a headless simulation core with ODE support, proven on two reference models.

### Added

- **Build**: Gradle 9.3.0 multi-project build, Scala 3.3.7 LTS on a JDK 22 toolchain, strict
  compiler flags, munit and ScalaCheck, Spotless/scalafmt, a nightly `slowTest` suite and
  GitHub Actions CI on Linux and macOS.
- **Core** (`modules/core`): units as opaque types; seeded `SplittableRng` with keyed streams;
  parameter schemas with defaults, ranges and named overrides; `Model`, `StepContext`,
  `Stepping` (fixed and adaptive) and `Probe`; `Run` with exact landing on sample times; run
  manifests with a dependency-free JSON codec, `ModelRegistry` and bit-exact replay; the pure
  `Simulation[S]` adapter.
- **ODE** (`modules/ode`): `OdeSystem`, RK4, Dormand–Prince 5(4) with step-size control and
  FSAL, velocity Verlet for separable systems; all allocation-free per step.
- **Verification** (`modules/verify`): observed convergence order, conservation drift and
  boundedness, determinism through manifest replay, per-step allocation.
- **Models**: Lorenz (golden values from a 40-digit reference, Lyapunov exponent check) and the
  double pendulum (energy probes, chaotic divergence checks).
- **Example**: `RunHeadless` command-line runner with CSV output, manifests and replay.
- **Documentation**: README, model pages in `docs/models`, contribution guide.

### Changed from the 0.1 plan

- Parameter schemas are declared with one `ParamSpec` per field and checked against the case
  class through its `Mirror`, instead of being derived from annotations (which would need a
  macro to read annotations and default arguments).
- `OdeModel` picks the integrator from the run's stepping (RK4 for fixed, Dormand–Prince for
  adaptive), so a manifest fully determines how a run is replayed.
- `-Wunused` covers imports, privates, locals and implicits but not parameters: numerical
  interfaces legitimately ignore arguments such as time or the random stream.
