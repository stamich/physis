# Waves

Module: `models/wave` · id `wave` · version 0.4.0

The damped wave equation on a 2D grid, with painted walls, a steady source and four kinds of
edge:

    u_tt = c² ∇²u − γ u_t + s(x, t)

`u` is the displacement, `c` the wave speed, `γ` the damping rate and `s` the source. Cells
marked in the **wall** mask are held at zero: waves reflect from them, and they can be painted
and erased while the model runs (see [interventions](../interventions.md)).

## Scheme

Leapfrog in time with the 5-point Laplacian, step `dt = 0.5`, grid spacing 1:

    u^{n+1} = 2u^n − u^{n−1} + (c dt)² L u^n − γ dt (u^n − u^{n−1}) + dt² s^n

The scheme is stable when the Courant number `c dt / h ≤ 1/√2`; with `dt = 0.5` that means
`c ≤ 1.41`. The model checks the limit with the largest eigenvalue of the stencil
(`Stencils.eigenvalue5`) when a run is built and when `c` changes live, and refuses a value
that would blow up.

Energy `½‖Δu/dt‖² + ½c²⟨u^{n+1}, −L u^n⟩` is conserved, up to rounding, by the undamped scheme with a
closed edge, which is what the energy probe shows.

## Parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `c` | 1.0 | 0.05 – 1.4 | wave speed |
| `damping` | 0 | 0 – 0.2 | damping rate |
| `edge` | 1 | 0 – 3 | border (restart only) |
| `sponge` | 24 | 4 – 64 | width of the absorbing layer |
| `frequency` | 0.2 | 0.01 – 1 | frequency of the source |
| `amplitude` | 0 | 0 – 2 | strength of the source (0 switches it off) |
| `nx`, `ny` | 256 | 64 – 1024 | grid size (restart only) |

The source is a point source in the cell containing the centre of the grid: each step adds
`dt² · amplitude · sin(2π f t)` there. The sine comes from a Taylor series, so a run gives the
same result on every platform. A wall on the source cell silences it.

## Edges

| `edge` | Border | Behaviour |
| --- | --- | --- |
| 0 | periodic | waves leave on one side and return on the other |
| 1 | reflective | `u = 0` outside: the echo has the opposite sign |
| 2 | free | zero slope: the echo keeps its sign |
| 3 | absorbing | a sponge layer of `sponge` cells with growing damping, sized for a reflection amplitude of about 10⁻³ |

## Presets

| Preset | Edge | What it paints |
| --- | --- | --- |
| Ripple tank | reflective | one pulse in a closed box |
| Double slit | absorbing | a wall with two openings in front of a steady source |
| Echo chamber | free | two blocks, one pulse, almost no damping |
| Open water | absorbing | nothing; a gentle source |
| Waveguide | reflective | a pulse in a painted channel |

A preset is a set of parameters plus ordinary interventions applied at step 0, so it appears in
the manifest like anything painted by hand. Applying one starts a new run.

## Datasets

`physis-cli export` and File → Export data… write the run as a NetCDF file with the fields `u` and `wall`, the probes, the time and a digest per record; see [datasets.md](../datasets.md).

## Verification

| Check | Threshold | Where |
| --- | --- | --- |
| A standing mode keeps the frequency of the discrete dispersion relation | scheme-exact | `WaveVerificationSuite` |
| Error against the continuum solution falls with order 2 under refinement | order 2 | `WaveVerificationSuite` |
| Without damping, energy is conserved on periodic, reflective and free grids | tight relative tolerance | `WaveVerificationSuite` |
| Uniform damping makes the amplitude decay as exp(−γt/2) | | `WaveVerificationSuite` |
| A plane pulse travels at c | 1% | `WaveSuite` |
| The guard accepts C up to 1/√2 and refuses more, at build and live | exact | `WaveSuite` |
| 64 × 64, 200 steps with a wall, a bump, damping and a source vs the numpy reference (`tools/wave_reference.py`) | 1e-10 per cell | `WaveReferenceSuite` |
| Golden digests after 1 000 steps (reflective and absorbing box) | equal | `WaveReferenceSuite` |
| An echo from a wall arrives after 2d/c; wall cells stay at zero; painting and erasing walls | within 2 cells | `WaveWallSuite` |
| The source drives its cell at its frequency | within 4 crossings | `WaveSourceSuite` |
| Closed edges keep the energy of a pulse; the sponge leaves less than 3% | 0.99 / 0.03 | `WaveEdgeSuite` |
| A reflective echo flips the sign, a free one keeps it | −0.3 / +0.3 | `WaveEdgeSuite` |
| Walls, pulses and a parameter change replay to the same digest | equal | `WaveInterventionSuite` |
| Every preset is valid, stays finite and replays | | `WavePresetsSuite` |
| A step allocates less than one byte | | `WaveSuite` |
| On 256 × 256 after 700 steps the absorbing edge keeps under 5% of the energy, the reflective one all of it | 0.05 / 1e-3 | `WaveSlowSuite` (slow) |
| At least 40 million cell updates per second on 256 × 256; a brush dab under 0.2 ms (median) | | `WaveSlowSuite` (slow) |

Speed in the Lab is 20 to 2 000 steps per second, 300 by default.
