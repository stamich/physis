# Double pendulum

Module: `models/pendulum` · id `double-pendulum` · version 0.1.0

## Equations

Point masses m₁, m₂ on massless rigid rods l₁, l₂; angles θ₁, θ₂ from the downward vertical.
State y = (θ₁, θ₂, ω₁, ω₂), Δ = θ₁ − θ₂, D = 2m₁ + m₂ − m₂ cos 2Δ:

```
θ₁' = ω₁
θ₂' = ω₂
ω₁' = [−g(2m₁ + m₂) sin θ₁ − m₂ g sin(θ₁ − 2θ₂) − 2 sin Δ · m₂ (ω₂² l₂ + ω₁² l₁ cos Δ)] / (l₁ D)
ω₂' = [2 sin Δ · (ω₁² l₁ (m₁ + m₂) + g (m₁ + m₂) cos θ₁ + ω₂² l₂ m₂ cos Δ)] / (l₂ D)
```

Total energy, conserved by the exact dynamics:

```
E = ½(m₁ + m₂) l₁² ω₁² + ½ m₂ l₂² ω₂² + m₂ l₁ l₂ ω₁ ω₂ cos Δ − (m₁ + m₂) g l₁ cos θ₁ − m₂ g l₂ cos θ₂
```

| Parameter | Default | Unit | Meaning |
| --- | --- | --- | --- |
| `m1`, `m2` | 1 | kg | masses |
| `l1`, `l2` | 1 | m | rod lengths |
| `g` | 9.81 | m/s² | gravity |
| `theta1`, `theta2` | 120, −10 | deg | initial angles (converted through the units API) |
| `omega1`, `omega2` | 0 | rad/s | initial angular velocities |

`PendulumParams.chaotic` (120° / −10°) and `PendulumParams.nearLinear` (10° / 10°) are the two
reference configurations.

## Integration

The angular accelerations depend on the angular velocities, so the system is **not separable**:
velocity Verlet does not apply and would not be symplectic here. The model uses RK4 for fixed
steps and Dormand–Prince 5(4) for adaptive steps, and energy is monitored as an accuracy check.
A symplectic option (implicit midpoint) may be added later.

Probes: `energy`, `energyDrift` (relative to the initial energy), `x2`, `y2` (outer bob position,
pivot at the origin, y up).

## Verification

| Check | Threshold | Where |
| --- | --- | --- |
| Energy drift, 10°/10°, RK4 dt = 1e-3, 10^5 steps | < 1e-6 | `PendulumSuite` |
| Energy drift, 120°/−10°, RK4 dt = 1e-3, t = 20 | < 1e-5 | `PendulumSuite` |
| Twins 1e-9° apart, chaotic, t = 20 | separation grows > 10^6 × | `PendulumSuite` |
| Twins 1e-9° apart, near-linear, t = 20 | separation grows < 10^3 × | `PendulumSuite` |
| Adaptive (tol 1e-10) vs fixed RK4 at t = 5 | < 1e-7 | `PendulumSuite` |
| Manifest replay | identical final digest | `PendulumSuite` |

The thresholds were checked against an independent double-precision simulation: energy drift
≈ 3e-14 (near-linear) and ≈ 1e-7 (chaotic); separation growth ≈ 4 × (near-linear) and
≈ 4 × 10^8 (chaotic).

## Views

`PendulumViews` (in `models/pendulum`, depending only on `physis-view`) describes how Physis Lab
draws the system:

- `mechanism(p)` — a 2D scene with the pivot, both rods and both bobs (areas proportional to
  mass), framed for the initial lengths, with a 2 000-point fading trail of the outer bob. The
  drawing reads lengths and masses from the current parameters, so it follows live changes;
- `energyDrift` — the relative energy drift probe over the last 30 simulated seconds;
- `default(p)` — the two side by side.

The bob positions are (l1 sin θ1, −l1 cos θ1) and that plus (l2 sin θ2, −l2 cos θ2); at θ = 0
both hang straight down. Note that the drift is measured against the energy at the start of the
run: after a live change of masses, lengths or g it shows the change in energy, not an
integration error.

The Lab runs the model with fixed RK4 steps of 0.0005 at one simulated second per second.

