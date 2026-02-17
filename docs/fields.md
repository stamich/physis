# Fields and agents

Milestone 0.3 adds two kinds of state next to ODE vectors: **fields** on a regular 2D grid
(`modules/fields`, package `physis.fields`) and **agents**, particles that look at their
neighbours (`physis.agents`, same module). Both keep the engine's rules: state is allocated once,
steps allocate nothing, randomness is keyed by step, and a run is reproducible from its manifest.

## Grid and field

`Grid2D(nx, ny, h = 1.0)` is an `nx` x `ny` grid of cells with spacing `h`, stored row-major with
a halo of one cell on every side. `Field2D(grid)` holds one `Array[Double]` for the cells and the
halo; `f(i, j)` is column `i`, row `j` (0 to `nx - 1`, 0 to `ny - 1`), and indices -1 and `nx`,
`ny` reach the halo. Row 0 is the first row; the Lab draws it at the top.

Helpers: `fill`, `copyFrom`, `sum`, `mean`, `min`, `max` over the interior, `digest` (a hash of
the interior, for golden tests) and `copyInteriorInto` (the interior without halo, for snapshots).

## Boundaries

`Boundaries(x, y)` with `Boundary.Periodic`, `Boundary.Dirichlet(value)` or `Boundary.Neumann`
fills the halo from the interior before a stencil reads it. The cell centres are half a cell
inside the boundary: periodic copies the opposite interior line (a torus), Dirichlet puts
`2 v - interior` in the halo so that the boundary value is `v`, Neumann copies the adjacent
interior value (zero flux). `Boundaries.both` and `Boundaries.dirichlet` build the usual pairs.

## Stencils

`Stencils.laplacian5(src, dst)` is the 5-point Laplacian, `Stencils.laplacian9` the isotropic
9-point one (its weights are in the scaladoc). Both read the halo, so fill it first. `eigenvalue5` and `eigenvalue9` give the
symbol of each stencil for a wave number, which is what the tests use to check the order and the
stability limit: forward Euler diffusion with the 9-point stencil is stable while
`dt * D <= 2 / 1.6 = 1.25` (the most negative eigenvalue is -1.6). Models check this in
`Model.check`, and `RunBuilder.build` and `Run.setParams` refuse parameters that break it.

## Particles and neighbours

`ParticleSet(count, names*)` keeps one array per named column (`x`, `y`, `vx`, `vy`) and exports
them as slices. `NeighborGrid(capacity, width, height, radius)` is a cell list on a periodic
world: `rebuild(x, y, n)` bins the particles with a stable counting sort and
`foreachNear(i) { (j, dx, dy, d2) => ... }` visits every other particle within `radius` exactly
once, in a fixed order, with the minimum-image displacement. It is `inline`, so the callback costs
no allocation. With fewer than three cells along an axis it falls back to scanning everything
(`usesFallback`), which is also correct.

The result of a neighbour search is checked against `Neighbours.bruteForce` in
`modules/verify`.

## Snapshots with slices

A model whose state holds fields or particles exports them through `StateExport.layout`, a list
of `Slice(name, offset, width, height)`. Every `Snapshot` carries the slices, so views refer to
data by name: `View.Heatmap("v")` shows the slice called `v`, `View.Particles()` reads `x`, `y`,
`vx`, `vy`. A scalar series is a slice of height 1.

## Restart-only parameters

Some parameters change the size of the state: the grid size, the number of particles. They are
marked `restart = true` in their `ParamSpec`. `Run.setParams` refuses them (they cannot change in
the middle of a run); `Command.Restart(values, seed)` builds a new run with those values and,
optionally, a new seed. The manifest of a live session that was restarted describes the run
since the last restart. Physis Lab marks these parameters with a star and applies them with an
Apply button.

## Verification

| Check | Where |
| --- | --- |
| Convergence order of both stencils (`Convergence.hasOrder`) | `FieldChecksSuite` (verify), `StencilsSuite` |
| Diffusion of a Fourier mode decays at the analytic rate; total mass is conserved | `DiffusionSuite` |
| Cell list equals brute force, including at the world edges | `NeighborGridSuite` |
| Steps allocate less than one byte on average | `NeighborGridSuite`, `GrayScottSuite`, `BoidsDeterminismSuite` |
| Unstable parameters are refused | `StabilityGuardSuite` |
