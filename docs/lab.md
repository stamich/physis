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
| Left | model picker, presets, parameter panel, tool box, seed field, run controls |
| Centre | the model's views (Lorenz: attractor above coordinates; pendulum: mechanism beside energy drift; Gray–Scott: u and v heatmaps above a statistics plot; Boids: the flock above its polarization; Waves: the wave with its walls in grey above energy and amplitude; Physarum: the trail with the agents as white dots above coverage and structures; Fluids: the dye with the tracer particles as faint white dots above kinetic energy and enstrophy; Convection: the temperature above kinetic energy and Nusselt number; Shallow water: the surface height above energy and highest surface) |
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
| Faster / slower (the range depends on the model: 0.01x to 100x for the ODE models, 10 to 4 000 steps/s for Gray–Scott, 0.1x to 20x for Boids, 20 to 2 000 steps/s for Waves, 5 to 600 steps/s for Physarum) | Speed slider | + / − |
| Rotate a 3D view | drag | |
| Zoom | scroll | |
| Default orientation and zoom | double-click | |
| Tool on / off (models with tools) | tool box | B |

Shortcuts are ignored while a text field has the focus or a stroke is being dragged.

## Saving images

**File → Save image** writes the first image of the view (the field as drawn, with a colour scale) as a PNG at 1×, 2×, 4× or 8×; see [export.md](export.md).

## Saving an animation

**File → Save animation…** turns the session so far into an animated PNG. It is on for models
with a picture (the field models; not Lorenz, the pendulum or Boids) and off while nothing has
run or while another animation is being saved.

The dialog shows a picture every *n* steps, the speed in pictures a second, the scale (1× to
16×), the number of loops (0 for ever), whether to add the colour scale, and whether to write the
manifest next to the file (`name.json`). Its first guess suits the model and the length of the
session, and a line under the fields gives the number of frames, the size of the picture, the size
of the file and about how long it will take. More than 600 frames is refused, and the line says
which *n* would fit.

The animation is not a recording of the screen. The Lab replays the session from its manifest, in
the background: strokes, parameter changes and presets come back at the steps where they
happened, and the result is the same bytes as `physis-cli replay <manifest> --animate` with the
same settings. The session keeps running meanwhile, and switching model does not disturb the save.
The status line and a progress bar show the frames written and the time left; **Cancel** stops it
and removes the file. Closing the window cancels it too. A fluid replay is slow (about 4 steps a
second at 256 × 256; a convection layer of 96 × 48 does about 450; the shallow-water sea is the cheapest of the fields), so look at the estimate first. Problems (nothing has run yet, a path that
cannot be written) appear in the status line.

## Exporting data

**File → Export data…** writes the session so far as a NetCDF file (see
[datasets.md](datasets.md)): the fields of the model (all the field models; Lorenz, the pendulum
and Boids give their probes alone), the probes, the time and a digest of the state at every
*n*-th step. The dialog asks for *n*, for the precision of the fields (32-bit floats halve the
file) and for the manifest next to the file, and a line under the fields gives the number of
records, the size of the file and about how long it will take. More than 1 GB is refused, and the
line says which *n* would fit.

Like an animation the file is made by replaying the session from its manifest in the background,
so it holds the data `physis-cli replay <manifest> --export` writes, and `physis-cli verify`
accepts it. The status line and the progress bar show the records written; **Cancel** stops it
and removes the file.

## Painting

Gray–Scott, Boids, Waves, Physarum, Fluids, Convection and Shallow water have a **tool box** under the parameters. At first no tool is on and
the pointer only reads cells. Choosing a tool turns the brush on: a ring follows the pointer and
shows its radius.

| Model | Tools |
| --- | --- |
| Gray–Scott | **Paint v** (sets v to a value; right button erases), **Erase v** |
| Waves | **Wall** (right button erases), **Erase wall**, **Pulse** (value: amplitude) |
| Boids | **Disturb** (value: strength) |
| Physarum | **Paint food** (right button erases), **Erase food**, **Paint trail** |
| Fluids | **Smoke** (dye, right button erases), **Push sideways** and **Push up** (value: speed, right button pushes the other way), **Wall** (right button erases), **Erase wall** |
| Convection | **Heat spot** and **Cool spot** (value: how much warmer or colder than conduction; the right button resets the place), **Wall** (right button erases), **Erase wall** |
| Shallow water | **Uplift** and **Drop** (value: metres; the right button does the opposite), **Land** (right button erases), **Erase land**, **Water level** (value: metres of a flat layer; the right button takes it away) |

The left button applies the tool and dragging paints a line; the right button erases where the
tool paints. The mouse wheel over the view changes the radius, the slider and the value field do
the same, B switches the tool off and on again. A drag is cut into dabs at most half a radius
apart, and the dabs of one frame are sent together. The box shows how many strokes the session
has recorded. On a periodic grid (Gray–Scott, Boids, Waves with the periodic edge) a stroke
wraps across the border. Painting works while the run is paused: the strokes are applied at the
next step.

Every dab is an [intervention](interventions.md), recorded in the manifest, so File → Save
manifest… and Replay manifest… work with painted sessions; the status line says how many
parameter changes and strokes were saved or replayed. The presets of Waves and Fluids paint their walls,
dye and pulses at step 0 and start a new run; Reset brings them back, painting you added by hand is
gone.

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
parameters, stepping, seed, every parameter change and every stroke as a step-indexed event, the
exact number of steps and the final state digest (format 3 when there are strokes). Because the run only ever takes whole steps and changes are
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
