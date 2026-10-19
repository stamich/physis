# Lorenz system

Module: `models/lorenz` · id `lorenz` · version 0.1.0

## Equations

Lorenz (1963), state y = (x, y, z):

```
dx/dt = σ (y − x)
dy/dt = x (ρ − z) − y
dz/dt = x y − β z
```

| Parameter | Default | Range | Meaning |
| --- | --- | --- | --- |
| `sigma` | 10 | 0 – 100 | Prandtl number |
| `rho` | 28 | 0 – 500 | Rayleigh number (normalised) |
| `beta` | 8/3 | 0 – 20 | geometric factor |
| `epsilon` | 0 | 0 – 1 | standard deviation of a seeded Gaussian perturbation of the initial state |

Initial state: (1, 1, 1), perturbed by `epsilon` using the run seed.

## Integration

- `Stepping.Fixed(dt)`: classic RK4. Stable and accurate for dt ≲ 0.01; dt = 1e-4 reproduces the
  reference to about 1e-12.
- `Stepping.Adaptive(...)`: Dormand–Prince 5(4) with Hairer-style error control.

The system has no conserved quantity, so accuracy is checked against a reference solution and
chaos against its known Lyapunov exponent.

## Verification

| Check | Threshold | Where |
| --- | --- | --- |
| State at t = 1 vs 40-digit reference | relative 1e-8 (adaptive, tol 1e-12), 1e-9 (RK4, dt 1e-4) | `LorenzSuite` |
| Pure `Simulation[S]` RK4 vs `OdeModel` RK4 | bit-identical after 10^4 steps | `ImmutableLorenzSuite` |
| Seeded perturbation | reproducible per seed | `LorenzSuite` |
| Manifest replay | identical final digest | `LorenzSuite` |
| Largest Lyapunov exponent (Benettin, t = 1000) | 0.85 – 0.95 | `LyapunovSuite` (slowTest) |

The reference values come from `tools/lorenz_reference.py` (mpmath Taylor-series integration at
40 significant digits) and are stored in `src/test/resources/lorenz-t1.csv`. Regenerating them
is a deliberate, reviewed change.

## References

- E. N. Lorenz, "Deterministic Nonperiodic Flow", *Journal of the Atmospheric Sciences* 20,
  130–141 (1963).
- G. Benettin, L. Galgani, A. Giorgilli, J.-M. Strelcyn, "Lyapunov characteristic exponents for
  smooth dynamical systems", *Meccanica* 15 (1980).
- E. Hairer, S. P. Nørsett, G. Wanner, *Solving Ordinary Differential Equations I*, 2nd ed.,
  Springer (1993), section II.4.
