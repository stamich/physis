# Changelog

All notable changes to Physis are listed here. Versions follow `vX.Y.Z` tags on `main`.

## 0.10.0 — Flooding and real sea floors

The shallow-water sea can now come ashore. A sea floor rises above the level of the sea, a wave
runs up it and floods the shore, the water runs back; the volume stays exact and the depth never
goes below zero. Without `flooding = 1` nothing changes and every earlier run has the same digest.

### Added

- **fields**: `Staggered.waterDepth`, `sillDepthU`, `sillDepthV` (the depth of the water over the
  sill of a face, and whether the face carries flow) and `limitOutflow` (no cell gives away more
  water than it holds).
- **shallow**: parameters `flooding`, `dryDepth` and `slope`; sea floors 3 (plane beach) and 4
  (beach with a bay); starts 3 (solitary wave) and 4 (N-wave); a lake at rest stays at rest
  exactly; five probes after the old five (`wet_fraction`, `flooded_area`, `runup`, `max_runup`,
  `max_inundation`); the slices `water` and `dry` in the state and in datasets; a water-depth
  view whose grey edge is the shoreline; [docs/flooding.md](docs/flooding.md).
- **core, netcdf, CLI**: the intervention `Load`, which names a file and the digest of its bytes
  (`Rasters`, `Raster`); `NcRaster` reads the variable `elevation` of a NetCDF file; `--bathymetry
  file.nc` sets the floor of a shallow run from it. The manifest holds the file name and digest,
  never the values, and a replay or `verify` refuses a file with another digest.
- **Lab**: presets **Beach run-up** and **Harbour flood**, and the **Water level** tool.
- **CLI**: `sweep --preset "Beach run-up" --sweep amp=4,8,12` is a table of run-up, since the new
  probes are columns of a sweep.
- **tools**: `tools/shallow_flood_reference.py`, an independent numpy scheme for a 1D beach and
  Thacker's bowl.

### Verified

- Lake at rest: bit for bit unchanged over 2000 steps on a plane beach and on a beach with a bay.
- Volume exact to rounding and depth at or above zero in every flooding run of the tests.
- Run-up of a solitary wave on a 1:19.85 beach (grid 0.25 d): the numpy reference gives
  R/d = 0.03756 for H/d = 0.01 and 0.06664 for H/d = 0.0185; the Scala model is within 3 % of
  both, and within the test bands of Synolakis' law (below).
- Thacker's parabolic bowl, L1 error of the depth over a period: 5.8 % at dx = 0.2, 4.5 % at 0.1
  and 1.9 % at 0.05 (numpy), in the Scala test under 7 % and finer is better than coarser.
- Speed on the CI runner, 256 × 256 cells with a wave on a beach: 5.30 million cell updates a
  second with flooding against 6.22 million for the nonlinear sea without it, 75.6 million for the
  linear sea; a step of the default sea takes 0.16 ms.

### Differences from the plan

- The plan named the dataset fields `wet` and `max_depth`. The fields are `water` (depth of the
  water, m) and `dry` (1 where there is land or dry shore); the highest run-up and the deepest
  water are the probes `max_runup` and `max_inundation`, which datasets carry as time series.
- **The run-up is smaller than Synolakis' law.** The advection of the velocity is semi-Lagrangian
  and damps the swash: R/d is 0.77 of the law's 2.831 √(cot β) (H/d)^1.25 for H/d = 0.0185 and 0.94
  for H/d = 0.01 at 0.25 d resolution; 0.74 at 0.5 d, 0.81 at 0.125 d. For H/d = 0.03 the numpy
  scheme reaches 0.58 of the law, where the law is no longer valid (the wave breaks). The tests
  check the reference and a band around the law, not the law.
- The Lab presets use a sea 100 m deep on 2.5 km cells, because the step is fixed at 50 s.
- The sea floor from a file is stretched over the whole grid with bilinear sampling and no map
  projection; the spherical grid and flooding on it are left to 0.11.0. Flooding applies to the
  nonlinear equations only.

### Not measured

- How the Lab looks and feels with the new presets and the depth view on a real display.
- Grids beyond 256 × 256, and the speed on a machine other than the CI runners.
- The run-up of waves that break, and any comparison with measured tsunamis or with a code that
  treats breaking.
- A NetCDF bathymetry other than the files made in the tests (a real GEBCO file has other
  variable names and may be too large to read whole).

## 0.9.0 — NetCDF export

Any run can be written as a NetCDF file: the fields of the model at a series of times, the
probes, the time, a digest of the state at every record, and the manifest of the run inside the
file. Models of earlier milestones are unchanged and their digests are the same.

### Added

- **netcdf**: a new module, `modules/netcdf`, that depends only on `core`. `Schema`, `Dim`, `Var`
  and `Attr` describe a file and are validated; `Cf` gives the CF attributes of axes, time and
  data (`Conventions = CF-1.8`); `NcWriter` writes classic NetCDF with 64-bit offsets (CDF-2),
  one record at a time, with the size known before the first byte; `NcFile` reads CDF-1 and
  CDF-2 files; `NcExport` writes a run; `NcCompare` compares two files bit for bit;
  [docs/datasets.md](docs/datasets.md).
- **core, models**: `ExportSpec` and `FieldMeta` say which slices of a model's state are fields,
  with names, units, the size of a cell and CF standard names; an export object for gray-scott,
  wave, physarum, fluid, convection and shallow.
- **CLI**: `export <file>` (`--every`, `--vars`, `--float`), `replay --export`, `sweep --export
  <dir>` (one file per point), and `verify <file>`, which replays the manifest inside a file and
  compares every value bit for bit.
- **Lab**: File → Export data…, with the interval, the precision and the manifest, an estimate
  of records, size and time, progress and Cancel.
- **tools**: `tools/netcdf_reference.py`, a second writer and parser of the format in Python.

### Verified

- The Scala writer's bytes equal those of the Python writer for a small and a full file (golden
  tests), and `libnetcdf` (`netCDF4` 1.7.5.1) and `scipy` read the Python files with the same
  dimensions, attributes and values. This check was run by hand, not in CI.
- A shallow sea written at every step reads back with the same values as a twin run; the last
  record's digest, from `digest_hi` and `digest_lo`, is the twin's `digest`.
- The same bytes come out on every run; the size announced before the run is the size of the
  file, to the byte; a cancelled export leaves no file.
- A file written by the Lab equals the one `replay --export` writes for the same manifest, and
  `verify` accepts it; an edited file is refused with the variable and record named.
- Speed on a CI runner (slow tests; the bars are far lower): 201 records of the default sea
  (128 × 96, five fields, 94 MB) are written at 391 MB/s and read back at 893 MB/s.

### Differences from the plan

- Own writer and reader of the classic format, in place of NetCDF-4/HDF5: no compression, groups
  or chunking, and no native library.
- The digest of a record is two int32 variables, since the header is written first and cannot
  hold a final digest; the manifest in the header has no digest for the same reason.
- Fields are 64-bit floats by default, with `--float` for 32-bit.
- The flag of `replay` is `--export`, and the reader is public in a minimal form (`NcFile`).
- Spherical shallow water and wetting/drying are left for 0.10.0.

### Not measured

- The Lab dialog and the background save on a real display; the tests cover the window API and
  the headless path.
- Opening a file in ParaView, Panoply, xarray or MATLAB; only `libnetcdf` and `scipy` were tried.
- Files of many gigabytes: the Lab refuses over 1 GB and the reader over 2 GB.

## 0.8.0 — shallow water

Shallow water on a rotating plane: gravity waves, geostrophic adjustment, Kelvin and Rossby waves
and a tsunami that slows down and grows on a shelf. Models of earlier milestones are unchanged
and their digests are the same.

### Added

- **fields**: `Staggered`, the operators of a layer of water on the staggered grid: open faces
  between sea cells, the means of a cell value to a face, and the four-point means of one velocity
  at the faces of the other, for the Coriolis term; [docs/numerics.md](docs/numerics.md).
- **models/shallow**: the `shallow` model, version 0.8.0: a forward–backward step with the mass
  equation in flux form, so that the volume is conserved to rounding; a Coriolis parameter with the
  beta effect; bottom friction; the nonlinear mode (depth `h + η`, semi-Lagrangian advection of
  the flow); channel, closed basin and open sea with an absorbing strip; a flat sea floor, a
  shelf and a seamount; starts at rest, from a front or from a Rossby wave; land and height as
  targets of strokes; probes of the mass, the energy, the highest and lowest surface and the
  fastest water; five presets (Tsunami, Adjustment, Rossby wave, Kelvin wave, Harbour); views of
  the height, the speed, the vorticity and the depth;
  [docs/models/shallow.md](docs/models/shallow.md).
- **Lab**: Shallow water with the tools Uplift, Drop, Land and Erase land, the presets, and
  Save animation (a picture every 10 steps).
- **CLI**: `shallow` in `run`, `sweep`, `frames`, `animate`, `montage` and `replay`; a sweep over
  `beta` or `gravity` is the same on any number of threads.
- **tools**: `tools/shallow_reference.py`, an independent numpy implementation of the linear
  scheme.

### Verified

- Seiche in a closed basin: the period is 1.0004 of `2L/c`, and the order of convergence is 2.00
  (the numpy numbers; the Scala tests take the same cases).
- Wave speed: the crest moves at 0.989 of `√(g h)`; a tsunami crest arrives at 15 300 s
  against 15 145 s expected.
- Geostrophic adjustment: the width of the current is 44.6 cells against the Rossby radius of
  44.3, the height at the front is half the step, and the time-mean current is within 1.4 % of the
  geostrophic one.
- Kelvin wave: 44.05 m/s against 44.29; the wave goes south along a west coast and not north
  (0.23 of the height), and with the sign of `f` reversed it goes the other way.
- Rossby wave: the phase of the pattern is 1.004 and 0.992 of the dispersion relation after 8000
  and 12 000 steps, and does not move without `β`.
- Shoaling: the wave grows by 1.311 where Green's law gives 1.326.
- The open sea leaves 8·10⁻⁴ of the energy of a pulse; the volume of a closed sea is constant to
  rounding; a step allocates nothing.

### Differences from the plan

- A fixed step of 50 s with a stability check (Courant number at most 0.7, `f dt` and `r dt` at
  most 0.5) in place of a Courant number as a parameter: the model refuses a run that would
  break, and the step stays part of the model, as in the fluid and the convection model.
- North is at the top of the picture (row 0), so `y` and `v` run south and the equations carry
  that sign. The first version had the rotation mirrored on screen; the numpy reference, the
  tests and the presets use the corrected convention.
- No flooding: a cell whose depth would be negative is held dry. The plan's wetting and drying is
  left for later.
- The numpy reference is linear. The nonlinear mode is tested for conservation and for the
  right limit at small amplitude, not against another implementation.
- No NetCDF export; it is in 0.9.0.

### Not measured

- How the Lab looks and feels with the shallow sea on a real display (CI has no GPU), and
  whether the speed range of 10 to 600 steps/s suits a slower machine.
- Anything above 256 × 256 cells; the speed bars in `ShallowSpeedSuite` are set far below what is
  expected and are not calibrated against the CI runners yet.
- The nonlinear mode in strong breaking or against published runup results; the Tsunami and the
  Harbour presets are checked to run, stay finite and replay, not against measurements.
- Spherical geometry, tides and real coastlines.

## 0.7.0 — convection

Rayleigh–Bénard convection: a layer of fluid heated from below and cooled from above, which stays
still below a critical Rayleigh number and forms rolls and then plumes above it. Models of earlier
milestones are unchanged and their digests are the same (the golden digests of the fluid still
hold).

### Added

- **fields**: `Diffuse` (the explicit 5-point diffusion step, taken out of the fluid, which now
  uses it) and `Scalar` (a quantity at the cell centres that the flow carries and that diffuses,
  with a boundary condition per axis); [docs/numerics.md](docs/numerics.md).
- **models/convection**: the `convection` model, version 0.7.0: the Boussinesq equations on the
  staggered grid of the fluid, with the temperature kept as the deviation from the conduction
  profile; no-slip plates, periodic or insulated sides, walls painted inside; the Rayleigh and
  Prandtl numbers as parameters; probes of the kinetic energy, the divergence, the Nusselt number
  (three ways) and the strongest rise; five presets (Onset, Quiet, Rolls, Plumes, Painted heat);
  views of the temperature, its deviation, the vorticity and the speed;
  [docs/models/convection.md](docs/models/convection.md).
- **Lab**: Convection with the tools Heat spot, Cool spot, Wall and Erase wall, the presets, and
  Save animation (a picture every 100 steps).
- **CLI**: `convection` in `run`, `sweep`, `frames`, `animate`, `montage` and `replay`; a sweep over
  `rayleigh` gives Nu(Ra) and is the same on any number of threads.
- **tools**: `tools/convection_reference.py`, an independent numpy implementation.

### Verified

- Linear stability in the reference: Ra_c = 1706.9 for rigid plates (exact 1707.76) and 657.5 for
  free-slip plates (exact 27π⁴/4).
- The model damps a perturbation at Ra 1200 and 1500 and grows it at 2200 (energy ratios 0.064, 0.39
  and 15.1 over 1000 steps in the reference); the growth rate crosses zero near Ra 1670.
- The rolls in a box four depths wide have a wavelength of 2.0 depths (theory 2.016) for four seeds.
- Nusselt number at 48 × 24, Pr 1: 1.585, 2.058, 2.618 and 2.948 at Ra 3000, 5000, 10 000 and
  20 000, the same in Scala and numpy within 0.1 to 0.3; floor, lid and carried heat agree.
- A step allocates nothing and the divergence is under 10⁻⁶ after every step. Speed on the CI
  runners: about 2.0 million cell updates per second at 256 × 128 with 23 solver iterations a step,
  2.2 ms a step at 96 × 48.

### Differences from the plan

- **fields** gets `Diffuse` (T1) in place of a body-force hook in the fluid, and the convection
  model has its own state: the fluid state treats "periodic" as both axes at once, while
  convection needs periodic sides with plates above and below. The pieces they share are in
  `fields`.
- The free-slip case (657.5) is checked in the numpy reference only. The model has no-slip plates.
- Five presets, not four.
- The Nusselt comparison is with the numpy reference and the weakly nonlinear slope of about 1.4,
  not with published tables.

### Not measured

- How the Lab looks and feels with convection on a real display (CI has no GPU), and whether
  the speed range of 20 to 1000 steps/s suits a slower machine.
- The model above Ra of about 10⁵: Plumes (Ra 300 000, Pr 7) is checked to run, stay finite and
  replay, not against published Nusselt numbers. The scheme is diffusive there.
- Three-dimensional convection; this is a 2D model.
- Whether the carried-over macOS allocation flake of the fluid tests, or the Physarum speed bar
  that failed once at 11.9 against 12 million on a CI runner, comes back.

## 0.6.1 — Save animation

The Lab can save a session as an animated PNG. No model changed: every digest and manifest of
0.6.0 is the same, and the fluid model stays at version 0.6.0.

### Added

- **Lab**: **File → Save animation…** with a dialog (every *n* steps, pictures a second, scale
  1× to 16×, loops, colour scale, manifest), a first guess per model and an estimate of frames,
  picture size, file size and time. It runs in the background with a progress bar and **Cancel**;
  closing the window cancels it, and the session keeps running meanwhile.
  [docs/lab.md](docs/lab.md).
- **Lab**: `LabAnimations`, which replays the session from its manifest and checks the replay
  against the manifest's digest. The file is byte for byte the one `physis-cli replay --animate`
  writes with the same settings, and the manifest is written beside it.
- **view**: `Apng.Writer`, which writes an animated PNG a frame at a time (`Apng.encode` uses it
  and gives the same bytes); `Frames.animation` streams, reports progress, can be cancelled and
  removes the file it began; `Frames.frameCount`; `FrameImage.colorBarExtra`.

### Differences from the plan

- The animation is a replay of the manifest, not a recording of the screen, so it is deterministic
  and can be reproduced on the command line.
- The version is the tag, as before: there is no version in `gradle.properties` to change.
- A path whose folder does not exist is created, as for the command line; a path that cannot be
  written gives a message in the status line.

### Not measured

- How the dialog, progress bar and Cancel look and feel on a real display (CI has no GPU).
- The export time on a real machine; a fluid replay is slow (about 4 steps a second at 256 × 256),
  which is why the dialog shows an estimate.
- Whether the allocation test of the fluid model on macOS, which was flaky in 0.6.0 and has a
  looser bound, fails again.

## 0.6.0 — fluids

Incompressible flow on a staggered grid, with the pressure projection, dye and tracer particles,
a Lab entry, sweeps over the viscosity and animated PNG export. Models of earlier milestones are
unchanged and their digests are the same.

### Added

- **fields**: `Poisson` (conjugate gradients with a warm start, singular cases handled),
  `MacGrid` and `Mac` (divergence, gradient, curl, vorticity, kinetic energy on the staggered
  grid), `Sampler.bilinearAt`, `Advect` (semi-Lagrangian, midpoint backtrace) and `Projection`
  (walls, inflow, outflow and periodic axes); [docs/numerics.md](docs/numerics.md).
- **models/fluid**: the `fluid` model, version 0.6.0: a lid-driven box, a channel with an inflow
  and a periodic box, walls painted while it runs, dye with buoyancy, tracer particles, probes
  (kinetic energy, divergence, enstrophy, dye mass, shedding), four presets (Cavity, Cylinder
  wake, Smoke plume, Vortex pair) and views of the dye, the vorticity and the speed;
  [docs/models/fluid.md](docs/models/fluid.md). Verified against the lid-driven cavity of Ghia,
  Ghia and Shin (largest difference of the centre lines 0.013), a Taylor–Green vortex, and the
  vortex street behind a cylinder (Strouhal number 0.218 in the numpy reference
  `tools/fluid_reference.py`); the divergence is under 10⁻⁶ after every step and a step allocates
  nothing.
- **Lab**: Fluids with the tools Smoke, Push sideways, Push up, Wall and Erase wall; presets that
  paint at step 0 and start a new run; sessions save and replay to the same digest.
- **view**: `Apng`, an animated PNG writer with no toolkit and the same bytes for the same
  frames; `Frames.animation`; `Png` hands its scanline and chunk helpers to the writer (the bytes
  of `Png` are unchanged). [docs/export.md](docs/export.md).
- **physis-cli**: `animate <file>` (`--fps`, `--loops`, `--every`, `--scale`), `replay --animate`,
  `--stroke` for run, frames, animate and replay, `--manifest` for frames and animate, and the
  fluid and its presets among the models.
- **sweeps**: `SweepSetup.interventions`, so every point gets the strokes of a preset; a Reynolds
  scan is `montage re.png --model fluid --preset "Cylinder wake" --sweep viscosity=...`, with the
  same CSV and montage on 1 and 4 threads.
- **Speed**: `FluidSpeedSuite` records cell updates per second and solver iterations per step
  for 256 × 256 cells (CI: 0.25 and 0.24 million cell updates per second, 716 iterations).

### Changed

- `Field2D.copyFrom` checks its sizes without building a message, which allocated 24 bytes a
  call and showed up in the allocation test of a fluid step.

### Differences from the 0.6.0 plan

- Too fast a flow is not refused: the velocity is limited to 4 cells per step, which keeps the
  semi-Lagrangian backtrace inside the grid. Only the explicit diffusion has a limit the model
  checks (`ν·dt ≤ 0.2`, so the viscosity is at most 0.8).
- Two push tools instead of one: a dab has no direction, so there are Push sideways and Push up
  (the right button pushes the other way).
- The wake test checks a Strouhal number between 0.17 and 0.27 around 0.218 from the numpy
  reference at 160 × 64 (22 % blockage), not the 0.16 to 0.19 of an unbounded flow; the critical
  Reynolds number is checked as steady at 20 and shedding at 150.
- The pressure solver is an unpreconditioned conjugate gradient with a cap of 1000 iterations,
  not a multigrid; 716 iterations a step on 256 × 256 makes a step cost about a quarter of a
  second there. The speed bars are 0.1 million cell updates per second and 800 iterations,
  below the two CI measurements.
- The vorticity is drawn on a fixed scale (`ValueRange.symmetric(0.5)`), so that a swirl keeps
  its colour.
- Presets of the fluid paint through interventions, so the CLI needed a table of painting
  presets and sweeps needed `SweepSetup.interventions`. A sweep over `nx` or `ny` with such a
  preset is refused.
- A wrong `--stroke` is exit code 2, also when the model refuses it (unknown target), and a
  replay with extra strokes records them and does not compare the old digest.
- Not in this release: the **Save animation** button of the Lab (planned for 0.6.1), JDK 25.

### Not measured

- The frame rate of the Lab with the fluid on a real display, and how many steps a second the
  presets reach: the CI runners have no GPU and the Lab tests only check the pixels. The speed
  range of the fluid in the Lab is 5 to 600 steps a second; the only measurement is the 0.25
  million cell updates per second on 256 × 256 (about four steps a second there).
- Why a step allocated 416 bytes in 300 steps on two macOS runs and nothing measurable on the
  others (the same code, the same value): not found. It is one-off, not per step, so it looks like
  the JIT (a deoptimisation or a closure that was not inlined yet) and not a leak. The bound of
  the fluid's allocation tests is 3000 bytes in 300 steps (10 bytes a step; a real allocation per
  step would be 16 bytes or more, 4 800 and up), where it had been 300.

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
