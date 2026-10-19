# Physis Lab

Physis Lab is the desktop app of Physis: it runs a model live, draws it every frame and
lets you change parameters while it runs. Sessions are reproducible: a session saved as a
manifest replays headless, bit for bit.

Screenshots rendered in CI (Linux, software rendering) are kept as the `lab-screenshots`
artifact of every CI run.

## Running

```bash
./gradlew :apps:lab:run
```

Requirements: JDK 22 (Gradle provisions it) and a desktop session. JavaFX 24 is downloaded
for the current platform (Linux, macOS, Windows; x64 and arm64) by the build.

## Window

| Area | Contents |
| --- | --- |
| Left | model picker, presets, parameter panel, seed field, run controls |
| Centre | the model's views (Lorenz: attractor above coordinates; pendulum: mechanism beside energy drift; Gray–Scott: u and v heatmaps above a statistics plot; Boids: the flock above its polarization) |
| Bottom | statistics: fps, p99 frame time, steps/s, simulated time, rejected steps, speed, state; the value of the heatmap cell under the pointer; status messages |
| Menu | File → Save manifest…, Replay manifest…, Close |

The parameter panel is generated from the model's `ParamSchema`: a slider plus an exact-value
field for every bounded real parameter (on a log scale when the spec says `Scale.Log`), a field
for unbounded ones, a spinner for integers, a check box for flags. Units are shown next to the
name, the documentation as a tooltip. Invalid input is outlined red and not sent.

Changes take effect from the next simulation step, without a restart. All changes made during
one frame are sent together, so dragging a slider sends at most one change per frame.

## Presets, seed and restart-only parameters

Models with named regimes show a **Preset** drop-down. Choosing a preset sets all its parameters
in one change, which is one event in the manifest.

Models with randomness show a **Seed** field and a **Reseed** button: the run starts again with
that seed and the parameters it has now.

Parameters that change the size of the state (grid size, number of boids, noise of the initial
seed) are marked with a star. Editing them does not touch the run; **Apply and restart** starts
a new run with the edited values, and Reset (R) discards them.

## Controls

| Action | Control | Key |
| --- | --- | --- |
| Pause / resume | Pause / Play | Space |
| One step (while paused) | Step | Right arrow |
| Restart from the initial state | Reset | R |
| Faster / slower (the range depends on the model: 0.01x to 100x for the ODE models, 10 to 4 000 steps/s for Gray–Scott, 0.1x to 20x for Boids) | Speed slider | + / − |
| Rotate a 3D view | drag | |
| Zoom | scroll | |
| Default orientation and zoom | double-click | |

Shortcuts are ignored while a text field has the focus.

## How it runs

Two threads, each owning its data:

- the **simulation thread** (`physis-sim`) owns the run. It advances in whole steps towards
  `speed × elapsed wall time`, spends at most 8 ms per burst, and publishes a snapshot (time,
  counters, state vector, probes, parameters) through a lock-free triple buffer;
- the **FX thread** owns the scene graph. On every pulse it takes the newest snapshot, appends
  it to the views' trails, redraws and sends queued commands.

If a model cannot keep up with the requested speed, it runs in slow motion instead of freezing
the window; the steps/s figure in the statistics shows what it achieves.

## Replaying a live session

File → Save manifest… writes the session as a format-2 manifest: model and version, initial
parameters, stepping, seed, every parameter change as a step-indexed event, the exact number of
steps and the final state digest. Because the run only ever takes whole steps and changes are
applied between steps, the session does not depend on timing: frame rate, pauses and speed
changes do not enter the manifest.

File → Replay manifest… re-runs a manifest headless and shows in the status line whether the
final digest matches the recorded one. The same manifest replays with the command-line runner:

```bash
./gradlew :examples:run --args="--replay /path/to/lorenz-session.json"
```

## Adding a model to the Lab

1. Describe its views next to the model with `physis-view` (no JavaFX): `View.Trajectory`,
   `View.Plot`, `View.Scene2D`, `View.Heatmap`, `View.Particles` and `View.Layout`, reading
   `Channel.StateAt` / `Channel.ProbeAt` or snapshot slices by name (see `LorenzViews`,
   `PendulumViews`, `GrayScottViews` and `BoidsViews`).
2. Add an entry to `apps/lab/.../Catalog.scala` with the parameters, stepping, probes, default
   view, presets and speed range (`OdeEntry` for ODE models, `ModelEntry` for the others), and
   the model to `Catalog.registry`.

## Performance

Heatmaps write their pixels into an int array that a JavaFX `PixelBuffer` wraps, so a frame of a
256 × 256 field is one pass over 65 536 cells (under 1 ms, checked by a `slowTest`) and one
buffer update. Particles are drawn in 16 heading bands, one path and one fill per band.

Trails are drawn in 32 colour bands, one canvas path per band, so a 20 000-point trail costs 32
stroke calls; preparing those paths takes well under 4 ms (checked by a `slowTest`). The target
on a typical laptop is a median of at least 58 fps with p99 frame time under 25 ms for Lorenz
with a full trail, read from the statistics line. CI runners have no GPU, so this is checked
by hand on a real display.
