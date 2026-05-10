# Convection

Module: `models/convection` · id `convection` · version 0.7.0

Rayleigh–Bénard convection in two dimensions: a layer of fluid between a hot floor and a cold
lid. Heated fluid is lighter and wants to rise, cooled fluid wants to sink; viscosity and heat
diffusion hold that back. Below a critical **Rayleigh number** nothing happens and heat only
conducts; above it the layer breaks into rolls, and at higher numbers into plumes that keep
changing.

In the Boussinesq approximation, with the temperature `T` in units of the difference `ΔT`
between floor and lid and lengths in units of the depth `H`:

    u_t + (u·∇)u = −∇p + ν ∇²u + a T ĵ ,   ∇·u = 0 ,   T_t + u·∇T = κ ∇²T

`a = g α ΔT` is the buoyancy, `ν` the viscosity and `κ` the thermal diffusivity. The numbers
that matter are `Ra = a H³ / (ν κ)` and the Prandtl number `Pr = ν / κ`.

## Scheme

The model keeps the **deviation** `θ = T − T_c` from the conduction profile `T_c = 1 − y/H`, which
is 0 on both plates, so that the same condition (`θ = 0`) holds at floor and lid. Then

    θ_t + u·∇θ = κ ∇²θ + w/H ,   u_t + … = … + a θ ĵ

where `w` is the vertical velocity: a fluid that goes up brings warmer fluid than what conduction
leaves there, hence `+ w/H`. The part of the buoyancy that belongs to `T_c` depends on the height
only and is balanced by the pressure, so it is left out.

One step of `dt = 0.25`, grid spacing 1, with the pieces of the fluid
([numerics.md](../numerics.md)):

1. close the faces of the walls; add the buoyancy `dt · a · θ` to `v` on the faces (θ averaged
   from the two cells); limit the velocity to 4 cells per step;
2. advect the velocity and `θ` with a semi-Lagrangian step (midpoint backtrace, bilinear reads);
3. add the heat the flow carries, `θ += dt · w / H`;
4. diffuse the velocity (no-slip plates) and `θ` (`Scalar`, `θ = 0` at floor and lid);
5. **project**: solve `B q = ∇·u` by conjugate gradients and subtract `∇q`, so that the velocity
   is free of divergence again (under 10⁻⁶ after every step, the `max_divergence` probe).

A step allocates nothing. The two diffusions are set from `Pr` so that the larger of them is
0.8 cells²/t, the most the explicit step allows (`D·dt ≤ 0.2`), and `a = Ra ν κ / H³` with `H = ny`
cells. The model refuses another step size, and supports fixed steps only.

## Parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `rayleigh` | 4000 | 100 – 10⁶ | Rayleigh number; convection starts at about 1708 |
| `prandtl` | 1 | 0.1 – 10 | viscosity over heat diffusion |
| `sides` | 0 | 0 – 1 | left and right borders (restart only): 0 periodic, 1 insulated walls |
| `nx` | 96 | 16 – 512 | grid columns (restart only) |
| `ny` | 48 | 16 – 256 | grid rows, the depth of the layer (restart only) |
| `kick` | 0.01 | 0 – 0.5 | size of the random temperature perturbation at the start (restart only) |

The floor is row 0, the lid the last row, and both are no-slip walls. A layer two depths wide
(`nx = 2 · ny`) holds one pair of rolls, the width the first instability prefers.

## Presets

| Preset | Sides | Ra | What it shows |
| --- | --- | --- | --- |
| Onset | periodic | 3000 | a small perturbation grows slowly into one pair of rolls |
| Quiet | periodic | 1200 | below the critical number the same kind of perturbation dies away |
| Rolls | periodic | 20 000 | two pairs of steady rolls (128 × 32) |
| Plumes | walls | 300 000 | Pr 7: hot plumes rise and cold ones fall, and the flow keeps changing |
| Painted heat | walls | 8000 | no perturbation; a hot spot at the floor and a cold spot at the lid start the rolls |

A preset sets parameters and, for the last one, paints heat at step 0 as ordinary
interventions, so it is part of the manifest. Choosing one in the Lab starts a new run.

## Probes and views

Probes, in this order: `kinetic_energy`, `max_divergence`, `nusselt`, `nusselt_floor`,
`nusselt_lid`, `max_up` (the largest upward velocity).

The **Nusselt number** is the heat transported divided by what conduction alone would carry; it is
1 at rest. `nusselt` is `1 + H⟨wθ⟩/κ` over all cells, `nusselt_floor` is `1 − 2H⟨θ⟩` of the first
row (the plate is half a cell below it), and `nusselt_lid` is `1 + 2H⟨θ⟩` of the last row. In a
steady state the three agree, which the tests check.

Slices of the state: `temperature` (`θ + T_c`, 1 at the floor, 0 at the lid), `deviation`,
`vorticity`, `speed` and `wall`. The Lab shows the temperature above plots of the kinetic energy
and the Nusselt number over the last 3000 time units; `ConvectionViews` also has the deviation
(a diverging map centred at zero: warm fluid that rises is red, cold fluid that sinks blue), the
vorticity and the speed.

## Interventions

| Target | Brush | Pulse |
| --- | --- | --- |
| `heat` | set, add or erase (back to conduction) the deviation in a disc | a Gaussian bump of heat, negative values cool |
| `wall` | paint or erase solid cells | not accepted |

Heat is a deviation: a hot spot of 0.5 raises the temperature by half of `ΔT` where it is
painted. Cells in a wall keep `θ = 0` and the flow goes around them. In the Lab the tools are
**Heat spot**, **Cool spot** (the right button resets the place), **Wall** and **Erase wall**.

## Checks

Against theory and against `tools/convection_reference.py`, an independent numpy implementation of
the same scheme (the numbers below are the ones it prints; `python tools/convection_reference.py
<critical | growth | nusselt | rolls | ratios>`):

- The linear problem `(D² − k²)² W = k² Ra Θ`, `(D² − k²) Θ = −W` gives the critical Rayleigh
  number 1706.9 for rigid plates (exact 1707.76) and 657.5 for free-slip plates (exact `27π⁴/4`).
  The model has rigid plates only; the free-slip case is checked in the reference, not in the
  model.
- The scheme grows a perturbation above the critical number and damps it below. In a layer two
  depths wide the growth rate (in units of `κ/H²`) is −2.06 at Ra 1200, −1.35 at 1500, +0.26 at
  1700, +1.78 at 1900, +3.92 at 2200 and +6.60 at 2600: it crosses zero near 1670, a little under
  the 1708 of the continuous problem because of the numerical diffusion. The Scala test takes
  the kinetic energy at 3000 steps over that at 2000: 0.064, 0.39 and 15.1 in the reference.
- In a box four depths wide the rolls settle to two pairs, a wavelength of 2.0 depths (linear
  theory: 2π/3.117 = 2.016), for every one of four seeds.
- Nusselt number at 48 × 24, Pr 1: 1.585 at Ra 3000, 2.058 at 5000, 2.618 at 10 000 and 2.948 at
  20 000, the same in the Scala model within the tolerances of the tests (0.1 to 0.3). Near
  onset the textbook slope is `Nu − 1 ≈ 1.4 (1 − Ra_c/Ra)` (it depends a little on `Pr`); the
  model gives 0.585 against 0.62 at Ra 3000.
- The heat leaving the floor, the heat entering the lid and the heat carried agree within 2 %
  and 5 % once the rolls have settled.

## Speed

On 256 × 128 cells the CI runners measure about 2.0 million cell updates per second with about 23
solver iterations per step, and a step of the default 96 × 48 layer takes about 2.2 ms (slow test
`ConvectionSpeedSuite`). The pressure solve is warm-started and the flow is smooth, so it needs far
fewer iterations than the fluid with obstacles.

## Limits

- Two dimensions, the Boussinesq approximation (the density varies only in the buoyancy) and
  constant diffusions. Real convection above Ra of about 10⁵ is three-dimensional.
- Bilinear semi-Lagrangian advection is diffusive: the critical number comes out a few percent
  low, and plumes at high Rayleigh number are smoother than in the exact flow. The Nusselt
  number is checked against the reference up to Ra 20 000, not against published values at
  high Ra.
- The plates are no-slip and at fixed temperature; the sides are periodic or insulated walls.
- Walls painted inside the layer have `θ = 0` (the conduction profile), not an insulating
  condition.
- The layer needs time: at Ra 3000 on 96 × 48 the rolls take about 15 000 steps (a diffusion
  time is `H²/κ`, 2900 time units).
