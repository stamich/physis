# Contributing to Physis

## Workflow

- Work happens in milestones (`0.1`, `0.2`, ...). Each milestone has a branch `milestone-X.Y`
  and one pull request into `main`.
- Every task of the milestone is **one commit**, in task order, named after the task
  (`T7 Add Run with fixed stepping`). Each commit compiles and passes `./gradlew build`.
- Releases are annotated tags `vX.Y.Z` on `main`, carrying a short release summary.

## Language

All documentation, comments, commit messages and identifiers are in English.

## Code

- Scala 3 (LTS), JDK 22, Gradle. Format with `./gradlew spotlessApply` before committing.
- Compiler warnings are errors.
- Models never depend on UI code; they describe views with `physis-view` only. The engine owns
  state, time and the step loop.
- `advance` and integrator steps must not allocate; add an `Allocation.perStep` test for new
  integrators.
- Randomness only through `SplittableRng` (`StepContext.rng`, the `rng` passed to `init`).

## Tests

A new model is accepted with:

1. a conserved-quantity check (`Conservation.drift` / `Conservation.bounded`) when the physics
   conserves something;
2. a convergence-order or reference-solution check;
3. a manifest replay check (`Determinism.replayMatches`);
4. a documentation page in `docs/models/` with equations, integration notes, thresholds and
   references.

Long validation runs go to the `slowTest` source set (`src/slowTest/scala`); CI runs them on every pull request and nightly.

Tests that need a display (JavaFX) check the `physis.fx.smoke` system property and are skipped unless it is `true`. CI sets it on Linux under a virtual display (`xvfb-run ./gradlew build -PfxSmoke=true`); run the same locally to include them.
