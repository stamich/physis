# Fluids

Module: `models/fluid` · id `fluid` · version 0.6.0

Incompressible flow in two dimensions, on a staggered (MAC) grid, with dye and tracer particles
to see it:

    u_t + (u·∇)u = −∇p + ν ∇²u + b·dye ĵ ,   ∇·u = 0

`u = (u, v)` is the velocity, `p` the pressure (the unit density is absorbed into it), `ν` the
viscosity and `b` the buoyancy: dye is lifted, so smoke rises. The dye `c` is carried by the flow
(`c_t + u·∇c = D ∇²c`). Cells in the **wall** mask are solid: nothing flows through them, and
they can be painted and erased while the model runs (see [interventions](../interventions.md)).

## Scheme

One step of `dt = 0.25`, grid spacing 1 (see [numerics.md](../numerics.md) for the pieces):

1. set the boundary values (walls at rest, the lid, the inflow);
2. add the buoyancy; limit the velocity to 4 cells per step;
3. advect the velocity with a semi-Lagrangian step (midpoint backtrace, bilinear reads);
4. diffuse the velocity and the dye explicitly;
5. **project**: solve `B q = ∇·u` by conjugate gradients to a relative tolerance of 10⁻⁹ and
   subtract `∇q`, so that the velocity is free of divergence again (under 10⁻⁶ after every
   step, checked by the `max_divergence` probe);
6. move the tracers with the new velocity.

The velocity lives on the faces of the cells (`u` on vertical faces, `v` on horizontal ones), the
pressure and the dye at the centres. A step allocates nothing: the fields are swapped by
reference, never copied.

The model refuses a viscosity or dye diffusion with `D·dt > 0.2`, where the explicit step would
blow up (viscosity up to 0.8). It supports fixed steps only.

## Parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `viscosity` | 0.1 | 0 – 0.8 | kinematic viscosity, cells²/t |
| `dyeDiffusion` | 0 | 0 – 0.8 | diffusion of the dye |
| `buoyancy` | 0 | −4 – 4 | lift of the dye, cells/t² (smoke rises when positive) |
| `lid` | 0 | −2 – 2 | speed of the top wall of a closed box, cells/t |
| `inflow` | 0 | 0 – 2 | speed of the flow entering a channel, cells/t |
| `edge` | 1 | 0 – 2 | border (restart only): 0 periodic, 1 closed box, 2 channel |
| `nx`, `ny` | 128 | 16 – 512 | grid size (restart only) |
| `tracers` | 0 | 0 – 20000 | particles carried by the flow (restart only) |

The **Reynolds number** of a flow is `U·L/ν`: the speed of the lid or the inflow times the size
of the box or the obstacle, over the viscosity.

## Edges

| `edge` | Border | Behaviour |
| --- | --- | --- |
| 0 | periodic | what leaves on one side comes back on the other |
| 1 | closed box | solid walls on all sides; the top one moves with `lid` |
| 2 | channel | `inflow` enters on the left, the flow leaves on the right, walls above and below |

## Presets

| Preset | Edge | What it paints |
| --- | --- | --- |
| Cavity | box | a lid-driven box at Reynolds number 100 with three layers of dye |
| Cylinder wake | channel | a cylinder at Reynolds number 150: the wake sheds a street of vortices |
| Smoke plume | box | smoke at the bottom that rises and rolls up into mushrooms |
| Vortex pair | periodic | two opposite jets that roll up into a pair of vortices |

A preset sets parameters and paints walls, dye and impulses at step 0 as ordinary
interventions, so it is part of the manifest. Choosing one in the Lab starts a new run.

## Probes and views

Probes: `kinetic_energy`, `max_divergence`, `enstrophy`, `dye_mass`, `shedding` (the vertical
velocity at the middle of the grid, which swings when vortices are shed).

Slices of the state: `dye`, `vorticity`, `speed`, `wall`, and the tracer positions `x`, `y`.
The Lab shows the dye with the tracers as faint white dots above plots of the energy and the
enstrophy; `FluidViews` also has the vorticity (a diverging map with a fixed scale, so that a
swirl keeps its colour) and the speed.

## Interventions

| Target | Brush | Pulse |
| --- | --- | --- |
| `dye` | set, add or erase dye in a disc | a Gaussian bump of dye |
| `wall` | paint or erase solid cells | not accepted |
| `u`, `v` | not accepted | a Gaussian push of the horizontal or vertical velocity |

Painting a wall closes the faces of the cells and clears the dye inside them; the next
projection makes the flow go around. A push is not free of divergence: the next projection
removes the part that is not a swirl, so a push makes a vortex, not a source.

## Checks

- A Taylor–Green vortex decays as `exp(−4νk²t)` within 10 %: the measured decay is 0.784 against
  the 0.771 of the exact solution, the difference being the numerical diffusion of the bilinear
  advection.
- Lid-driven cavity at Reynolds number 100 on 64 × 64 (slow test): the centre-line velocity is
  within 0.013 of the table of Ghia, Ghia and Shin (1982) (the test allows 0.05), within 2·10⁻³ of
  the independent numpy implementation `tools/fluid_reference.py`, and closer on 64² than on 32².
- Flow past a cylinder (slow test, 160 × 64, diameter 14): at Reynolds number 150 the wake sheds
  a street whose Strouhal number is about 0.22 (numpy reference 0.218; the channel is narrow, 22 %
  blockage, which raises it above the 0.16 – 0.19 of an unbounded flow); at 20 it stays steady.
- Golden digests of two short runs pin the bits on Linux and macOS.

## Speed

On 256 × 256 cells the CI runners measure about 0.25 million cell updates per second, with 716
solver iterations per step (slow test `FluidSpeedSuite`). The pressure solve is an
unpreconditioned conjugate gradient, so the iterations grow with the grid.

## Limits

- The velocity is limited to 4 cells per step instead of refusing a run whose flow would exceed
  the Courant number: the semi-Lagrangian step stays stable at any speed, but the limit keeps the
  backtrace inside the grid.
- Bilinear advection is diffusive (about `U·h/2·(1 − c)` for a Courant number `c`): small
  vortices decay faster than in the exact flow, and a wake shed at low viscosity fades.
- The grid has square cells and walls are staircases.
