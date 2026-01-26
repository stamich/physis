# Changelog

All notable changes to Physis are listed here. Versions follow `vX.Y.Z` tags on `master`.

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
