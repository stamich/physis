# Changelog

All notable changes to Physis are listed here. Versions follow `vX.Y.Z` tags on `main`.

## 0.5.1 — physis-cli

Patch release: the command line that 0.5 left in `examples`, tests that no longer fail on a loaded
machine, and speed bars set from what CI measured. No model changed and no digest changed.

### Added

- **physis-cli** (`apps/cli`): `run`, `frames <dir>`, `sweep`, `montage <png>`, `replay <manifest>`,
  `list` and `describe <model>`, with `--help` for every command and `--version`. A command
  knows which options belong to it, and exit codes are 0 (done), 1 (the run failed) and 2 (wrong
  command line); [docs/cli.md](docs/cli.md). `./gradlew :apps:cli:installDist` makes the
  `physis-cli` start script, which CI runs from an empty directory.
- `--preset <name>` for run, frames and sweep (Gray–Scott, Boids and Physarum have presets).
- Sweeps: `# done/total` on standard error (`--quiet` turns it off), `--dry-run` (the axes and the
  number of points, nothing run) and `--fail-fast` (every point checked against the schema and the
  model's limits first). Manifests written by a sweep carry the digest of their point.
- Replay checks the digest recorded in the manifest and exits with 1 and a message when it
  differs; `run --manifest` now writes its manifest after the run, with the digest.
- **verify**: `SpeedReport`; the slow-tests job prints the measured speeds in the job summary and
  as an annotation.

### Changed

- `RunHeadless` in `examples` is a thin wrapper around `physis.cli.Headless`; its options and
  output are those of 0.5.
- The publishing-gap test of `LiveRunSuite` and the Boids throughput test take the best of several
  windows instead of one (they failed on loaded runners after the 0.5 merge; separate PR).
- Speed bars from CI: Physarum 12 million agent steps per second on 512 × 512, best of five
  windows (CI measured 31.5 on one run and 16.1 on another, so the 0.5 plan's 25 would have
  failed), sweep scaling 1.5 with 4 threads (measured 2.07 and 2.04; the 0.5 plan had 2.5), PNG
  512 × 512 under 30 ms (measured 0.9 and 1.6 ms).

### Differences from the 0.5.1 plan

- T4 and T5 changed places: the module and the move of the engine come first, then the commands.
- The command is `physis-cli`; `--fail-fast` checks the points before running any, it does not
  stop a sweep that has started.
- Not done: `--format png` for frames (PNG is the only format), an estimated run time in
  `--dry-run`, interventions on the command line.
- The test of the documentation runs the examples of `docs/cli.md` only.

## 0.5.0 — agents in fields, export and sweeps

Fifth milestone: a model where agents and a field act on each other, runs in bulk, and pictures
out of the box.

### Added

- **Sweeps** (`modules/core`): `ParamGrid` (`a:b:n`, `log:a:b:n`, lists, `seed=a..b`), `Sweep.run`
  on a fixed pool of threads with results in grid order for any thread count, `SweepOutput`
  (CSV, JSON Lines, a manifest per point); [docs/sweeps.md](docs/sweeps.md).
- **Images** (`modules/view`): `Png`, an own PNG writer with stored deflate blocks (the same bytes
  on every platform; `compress` is optional), `FrameImage`, `Frames`, `Montage`;
  [docs/export.md](docs/export.md).
- **Fields** (`modules/fields`): `Sampler` and `Deposit` (nearest and bilinear, periodic) to couple
  agents and fields, and `TrailOps` (3 × 3 diffusion, decay).
- **Physarum** (`models/physarum`): after Jones (2010), with food and trail strokes, three presets,
  probes for trail mass, coverage and structures, an independent numpy reference and a golden
  digest equal on Ubuntu and macOS; [docs/models/physarum.md](docs/models/physarum.md).
- **View**: `View.Layers` (a heatmap with agents drawn as dots).
- **Physis Lab**: Physarum (5–600 steps/s) with its tools; File → Save image at 1×–8×.
- **Command line** (`examples`, `RunHeadless`): `--list`, `--steps`, `--frames`, `--sweep`,
  `--montage`, wave and Physarum, and a replay that carries events and strokes;
  [docs/cli.md](docs/cli.md).
- **Tests**: a slow test that classifies a 5 × 5 patch of the Gray–Scott F–k map against the
  reference (at least 20 of 25), and speed bars for Physarum, sweep scaling and PNG writing.

### Changed from the 0.5 plan

- The command line stays in `examples` (`RunHeadless`); `apps/cli` is left for 0.5.1.
- PNG is written by Physis itself, not by `javax.imageio`.
- The coupled model is Physarum.
- The JDK stays at 22; JDK 25 is not used.
- The Physarum speed bar starts low (5 million agent steps per second) and is raised after it has
  been measured on CI.

## 0.4.0 — waves and interventions

Fourth milestone: painting into a running model. Brush strokes, pulses and disturbances are
recorded as interventions, so a painted session replays bit for bit, and the wave equation is the
first model made for it.

### Added

- **Interventions** (`modules/core`): `Intervention.Brush`, `Pulse` and `Disturb`, `Disc` and
  `Op`; `Model.accepts` and `Model.intervene` (both refuse by default); `Run.intervene`, applied
  before the next step after the parameter changes; `RunBuilder.interventions`; manifest format 3
  (format 2 is still written when there are no strokes); `Command.Intervene` and
  `Command.Restart(values, seed, painted)`; `Snapshot.interventions`; see
  [docs/interventions.md](docs/interventions.md).
- **Fields** (`modules/fields`): `Brush.foreachCell` (allocation-free, periodic, each cell once),
  `Mask` and `Pulse`.
- **Waves** (`models/wave`): the damped wave equation with leapfrog, a wall mask, a point source,
  periodic, reflective, free and absorbing (sponge) edges, a Courant guard, an energy probe, five
  presets, an independent numpy reference (`tools/wave_reference.py`) and golden digests;
  [docs/models/wave.md](docs/models/wave.md).
- **Gray–Scott and Boids**: a brush on u and v; a disturbance of the boids inside a circle.
- **physis-view**: `ValueRange.Symmetric` and `SymmetricAuto`, a heatmap overlay (walls in flat
  grey), `StrokePlanner` (dabs at most half a radius apart, wrapped across seams).
- **physis-fx**: pointer tools on heatmaps and on the particle view (`Paintable`), the brush ring,
  the mouse wheel for the radius, `ToolBox` with a stroke counter.
- **Physis Lab**: Waves (20–2 000 steps/s) and the tool box of Gray–Scott, Boids and Waves; B
  switches the tool; the status line reports strokes; a double-slit screenshot in CI.
- **Tests**: slow tests for the wave (40 million cell updates per second, a dab under 0.2 ms, the
  absorbing edge on 256 × 256), Lab tests for painting, wave presets and painted sessions that
  replay to the same digest.

### Changed from the 0.4 plan

- The edge is a restart-only parameter (`edge`), and the absorbing edge is a sponge layer, not a
  one-way boundary condition.
- Boids only take `Disturb`; they do not get an eraser or a way to add agents.
- The versions of the Gray–Scott and Boids models stay 0.3.0: a run without interventions is
  unchanged.
- Applying a preset with painting is one `Command.Restart` that carries the painting, so the
  walls are part of the new definition and Reset brings them back.
- JDK 25 is not used in this milestone; the build stays on JDK 22.
- Not measured here: the frame rate and the feel of a stroke on a real display (the plan's manual
  check); CI runners have no GPU.

## 0.3.0 — fields and agents

Third milestone: numerical fields and agent-based models. Two new models, Gray–Scott
reaction–diffusion and Boids, run live in Physis Lab with presets, seeds and replayable sessions.

### Added

- **Fields** (`modules/fields`): `Grid2D` and `Field2D` with a one-cell halo; periodic,
  Dirichlet and Neumann boundaries; 5- and 9-point Laplacians with their symbols; see
  [docs/fields.md](docs/fields.md).
- **Agents** (`modules/fields`): `ParticleSet` (named columns, slices, digest) and `NeighborGrid`,
  a cell list with a stable counting sort and an allocation-free inline `foreachNear`.
- **Core**: `Digest.ofRange`, `fold` and `start`; `Model.check(params, stepping)`, run when a
  run is built and when parameters change live, so unstable values are refused with a message;
  `ParamSpec.restart` and `Command.Restart(values, seed)` for parameters that change the size of
  the state; `Snapshot.slices` and `StateExport.layout` for named parts of the state.
- **Verification** (`modules/verify`): `Convergence.hasOrder`, `Conservation.nonIncreasing`,
  `Neighbours` (brute-force search, displacement, comparison of neighbour sets).
- **Gray–Scott** (`models/grayscott`): Pearson's model with five presets, probes, stability limit
  `D·dt ≤ 1.25`, an independent numpy reference (`tools/gray_scott_reference.py`) and golden data;
  [docs/models/gray-scott.md](docs/models/gray-scott.md).
- **Boids** (`models/boids`): Reynolds' rules with a counter-based random turn, three presets
  including the disordered Vicsek model, an independent reference for the thresholds
  (`tools/boids_reference.py`); [docs/models/boids.md](docs/models/boids.md).
- **physis-view**: `View.Heatmap`, `View.Particles`, `ValueRange`, `HeatmapRaster` (field values
  to packed ARGB through a 256-entry table).
- **physis-fx**: `HeatmapRenderer` (JavaFX `PixelBuffer`, with `PixelWriter` as fallback),
  `ColorBar`, `ParticlesRenderer` (16 heading bands, one fill each, wrapped copies at the edges),
  `PresetPicker`, `SeedBar`; `RunControls` takes the speed range and its text from the model;
  `ParamPanel` marks restart-only parameters and applies them together.
- **Physis Lab**: Gray–Scott (10–4 000 steps/s, 600 by default) and Boids (0.1x–20x); preset
  drop-down, seed field with Reseed, Apply for restart-only parameters, the value of the cell
  under the pointer in the bottom bar.
- **Examples**: `RunHeadless` runs `gray-scott` and `boids` with the model's own step, prints
  their probes as CSV, writes and replays manifests.
- **Tests**: slow throughput tests (Gray–Scott 15 million cell updates per second, 2 000 boids
  500 steps per second), a Lab session test for Gray–Scott with parameter changes and a restart,
  and screenshots of both new models as CI artifacts.

### Changed from the 0.3 plan

- The Worms preset uses F 0.0540, k 0.0629; the planned 0.078 / 0.061 died out in the reference.
- The Gray–Scott reference run is 64 × 64 (a seed of 3 cells on 32 × 32 dies out in 100 steps),
  and the regime tests run without noise, as the reference does.
- Allocation tests assert less than one byte per step instead of exactly zero: a fixed overhead
  of a few dozen bytes is not a per-step cost.
- Boids use a Taylor series for the random rotation instead of `StrictMath`, which allocates in
  its trigonometry; the result is still identical on every platform.
- Drawing into the field with a brush is not part of 0.3; it moves to 0.4 together with a
  replayable brush.
- The slow tests run on every pull request (as in 0.2).

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
