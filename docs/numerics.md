# Numerics of the fluid

The pieces of the fluid model, all in `modules/fields` so that other models can use them. The
model itself is described in [models/fluid.md](models/fluid.md).

## Staggered grid

`MacGrid(nx, ny, h)` describes a grid of `nx × ny` cells with the velocity on the faces: `u` on
a `Grid2D(nx + 1, ny)` (vertical faces), `v` on a `Grid2D(nx, ny + 1)` (horizontal faces). The
pressure and the dye are at the cell centres. This keeps the divergence and the pressure gradient
as compact differences and avoids the checkerboard of a collocated grid.

`Mac` has the operators: `divergence` of a face field into the cells, `gradient` of a cell field
into the faces, `curl` of a stream function, `vorticity` and `kineticEnergy`. On a periodic axis
the last face is a copy of the first and is kept in step after every operation.

## Poisson solver

`Poisson` solves `B q = r` with `B q = Σ open·(q_c − q_nb)`: the 5-point Laplacian with the sign
changed, summed over the open faces only, so walls and boundaries are part of the matrix. The
method is conjugate gradients with a warm start from the pressure of the previous step. When no
face is open to the outside (a closed box, a periodic grid) the matrix is singular; the mean of
the residual is removed, which is the compatibility condition of the problem. The loop stops at a
relative residual of 10⁻⁹ or 1000 iterations and allocates nothing.

## Projection

`Projection.project` makes a velocity field free of divergence: it solves `B q = ∇·u` and sets
`u += open·(q(i) − q(i−1))` on the faces. Faces next to a wall are closed and not touched: the
caller sets them (zero for a wall, the inflow, the lid) before the projection. The divergence
after a projection is the solver tolerance, under 10⁻⁶ in the fluid tests.

## Advection

`Advect` is the semi-Lagrangian step: to move a quantity, trace the position of each sample back
along the velocity (midpoint rule, two reads of the velocity) and read the old value there with
`Sampler.bilinearAt`. The result is unconditionally stable, and costs numerical diffusion of
about `U·h/2·(1 − c)` for a Courant number `c = U·dt/h`. The velocity is clamped to 4 cells per
step. Reads past the border wrap on a periodic axis and clamp otherwise.

## Diffusion

Viscosity and dye diffusion are explicit: `f += D·dt·∇²f` with the 5-point Laplacian and ghost
values mirrored at walls. Stable for `D·dt ≤ 0.2`, which the model checks. The step is
`Diffuse.step` (every cell) and `Diffuse.stepOpen` (only the faces that are open, for the
velocity), shared by the fluid and the convection model; a cosine mode decays by exactly the
discrete factor `1 − s (2 − 2 cos(2π/n))`, which the tests use.

## Scalars

`Scalar` is a quantity at the cell centres that the flow carries and that diffuses (the dye, a
temperature): two copies swapped by reference, `advect(flow, dt)` with the semi-Lagrangian step
and `diffuse(s)` with a boundary condition per axis (periodic, Dirichlet or Neumann; see
`Boundaries`). Advection reads edge values as they are, so a Dirichlet wall acts through
diffusion only, which is right for a wall at rest. The convection model keeps the *deviation*
from the conduction profile in a `Scalar` with `Dirichlet(0)` at floor and lid and a periodic
or Neumann `x`, and adds the heat the flow carries as a source `w/H` (see
[convection.md](models/convection.md)). Periodic in `x` and walls in `y` need the per-axis
flags of `Projection` and `Advect.Flow`, which the fluid sets to the same value for both axes.

## Reference

`tools/fluid_reference.py` is an independent numpy implementation of the same scheme with the
table of Ghia, Ghia and Shin for the cavity, the Taylor–Green decay and the wake. The slow tests
compare the Scala model with it and with the table. `tools/convection_reference.py` does the
same for the convection model: the critical Rayleigh numbers of the linear problem, growth
rates, roll wavelength and Nusselt numbers.

## Determinism

All of it is plain loops over arrays in a fixed order, with no parallelism. The only
transcendental function is `exp`, in the Gaussian of an impulse, taken from `Math`; the golden
digests in `FluidSuite` are the same on Linux and macOS. The tracers respawn from a hash of their
index and the step, not from a shared random generator, so they do not depend on the order of
anything else.
