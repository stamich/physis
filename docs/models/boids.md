# Boids

Module: `models/boids` · id `boids` · version 0.3.0

## Rules

Reynolds (1986) on a periodic 2D world. Every step each boid looks at the neighbours within
`perception` (minimum-image distance, found with a cell list from [fields](../fields.md)) and
forms three dimensionless steering terms:

- **separation**: the sum of `−d / |d|²` over neighbours closer than `separationDist`, times
  `separationDist` (a neighbour exactly at that distance counts 1, a closer one more);
- **alignment**: the mean neighbour velocity minus its own, over `maxSpeed`;
- **cohesion**: the mean displacement to the neighbours, over `perception`.

The weighted sum is limited to length 1 and scaled by `maxForce`, the largest change of velocity
per step. The velocity is then turned by a uniform random angle in `[−wander/2, wander/2]`, its
speed is clamped to `[minSpeed, maxSpeed]`, and the boid moves by one velocity and wraps. All new
velocities are computed from the old state (double-buffered), so the result does not depend on
the order of the boids.

With `wSeparation = wCohesion = 0` the model is the **Vicsek model**: alignment against noise, an
order–disorder transition in which the polarization falls as `wander` grows.

| Parameter | Default | Range | Meaning |
| --- | --- | --- | --- |
| `perception` | 40 | 5 – 200 px | neighbour radius |
| `separationDist` | 14 | 1 – 100 px | personal space |
| `wSeparation`, `wAlignment`, `wCohesion` | 1.5, 1.0, 0.8 | 0 – 5 | weights of the three rules |
| `maxSpeed`, `minSpeed` | 4.0, 1.5 | 0.5 – 12, 0 – 12 px/step | speed limits (`minSpeed ≤ maxSpeed`) |
| `maxForce` | 0.15 | 0.01 – 2 | largest velocity change per step |
| `wander` | 0.1 | 0 – 3 rad | random turn per step |
| `n` | 1500 | 2 – 20 000 | number of boids (**restart only**) |
| `width`, `height` | 1200, 800 | 100 – 5000 px | world size (**restart only**) |

## Randomness

The random turn is a pure function of a key drawn at the start, the step and the boid index
(a counter-based generator in integer arithmetic), not of a stream. The rotation uses a Taylor
series in plain arithmetic instead of `StrictMath`, which allocates in its trigonometry. So a
step allocates nothing, and a run is bit-identical on every platform and in any order of boids.
One step is 1/60 time unit (`Boids.stepping`).

## Presets

| Preset | Weights (separation, alignment, cohesion) | wander | Behaviour |
| --- | --- | --- | --- |
| Flock | 1.5, 1.0, 0.8 | 0.1 | a loose, coherent flock |
| Swarm | 1.0, 0.2, 2.0 | 0.3 | strong cohesion, little alignment: a milling swarm |
| Vicsek (disordered) | 0, 1.0, 0 | 2.5 | alignment only with strong noise: no collective motion |

## Probes and views

Probes: `polarization` (0 for random headings, 1 for a perfectly aligned flock), `mean_speed`
and `mean_neighbours`. The Lab draws the flock as small triangles pointing along the velocity,
coloured by heading in 16 bands of a cyclic colormap (one path and one fill per band, so a frame
costs 16 fill calls whatever the number of boids), above a plot of the polarization over the last
10 time units. A boid near an edge is drawn again on the opposite side.

Speed in the Lab is 0.1x to 20x, 1x by default (60 steps per second).

## Verification

| Check | Threshold | Where |
| --- | --- | --- |
| Alignment only, low noise: polarization from below 0.3 to above 0.9 in 600 steps | 3 seeds | `VicsekSuite` |
| Alignment only, strong noise: polarization stays below 0.3 | 3 seeds | `VicsekSuite` |
| Polarization falls as the noise grows; changing the noise live breaks up an ordered flock | | `VicsekSuite` |
| Thresholds from an independent brute-force implementation (`tools/boids_reference.py`) | statistical, not bit for bit | `VicsekSuite` |
| Result independent of the order of the boids | | `BoidsSuite` |
| Speeds and positions stay within bounds for 500 steps with every rule on | | `BoidsSuite` |
| Two boids alone: separation pushes apart, cohesion pulls together | | `BoidsSuite` |
| Sine and cosine series vs `StrictMath` on [−1.5, 1.5] | 1e-15 | `BoidsSuite` |
| Golden digest of 500 boids, seed 7, 300 steps | equal on every platform | `BoidsDeterminismSuite` |
| Live session with three changes and a restart replays | same digest | `BoidsDeterminismSuite` |
| A step allocates less than one byte per step | | `BoidsDeterminismSuite` |
| 2 000 boids at least 500 steps per second | | `ThroughputSuite` (slow) |
