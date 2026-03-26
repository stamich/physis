# Physarum

Module: `models/physarum` · id `physarum` · version 0.5.0

Agents that follow and lay a chemical trail, after Jones (2010), a model of the slime mould
*Physarum polycephalum*. Thousands of simple agents on a periodic grid produce transport
networks, meshes or spots, depending on how they sense and turn. It is the first model in which
agents and a field act on each other: the agents read the trail, and the field is changed by them.

## Rules

Each step, for every agent in index order:

1. **Sense.** Three sensors sit `sensorDist` ahead, at the heading and at ±`sensorAngle`. Each
   reads the trail (plus `foodWeight` × the food field) at the nearest cell.
2. **Turn.** Turn towards the strongest sensor by `turnAngle`; if the front is weakest, turn left
   or right at random; if the sides are stronger than the front, turn at random. The random
   number comes from a counter-based generator keyed by seed, step and agent index, so it does
   not depend on any order of evaluation.
3. **Move** `stepSize` along the new heading and wrap at the border.
4. **Deposit** `deposit` into the trail cell, in index order.

Then the trail diffuses (a 3 × 3 mean, mixed with the old value by `diffusion`) and fades by
`1 − decay`. Headings are unit vectors turned with a sine and cosine from Taylor series, so a run
is the same bit for bit on every platform.

## Parameters

| Name | Default | Range | Meaning |
| --- | --- | --- | --- |
| `sensorAngle` | 0.7 | 0.1 – 1.5 | angle between the front and the side sensors (rad) |
| `sensorDist` | 9 | 1 – 30 | distance of the sensors (cells) |
| `turnAngle` | 0.6 | 0.05 – 1.5 | turn per step (rad) |
| `stepSize` | 1 | 0.2 – 3 | move per step (cells), at most `sensorDist` |
| `deposit` | 5 | 0.1 – 50 | trail laid per agent per step |
| `decay` | 0.1 | 0 – 0.5 | fraction of the trail lost per step |
| `diffusion` | 1 | 0 – 1 | how much of the 3 × 3 mean replaces the cell |
| `foodWeight` | 1 | 0 – 10 | pull of the food field |
| `n` | 40 000 | 10 – 1 000 000 | agents (restart only) |
| `nx`, `ny` | 256 | 64 – 1024 | grid size (restart only); twice `sensorDist` must be less than the smaller side |

## Presets

| Preset | What it shows |
| --- | --- |
| Network | the defaults: veins that connect and thicken |
| Mesh | wide sensors and a slow fade: a loose net with large cells |
| Spots | close, wide sensors and sharp turns: isolated clusters |

## Probes and views

`trail_mass`, `coverage` (the share of cells above twice the mean trail) and `structures` (the
number of 4-connected pieces of those cells, with wrapping). The view is the trail as a heatmap
with the agents drawn over it as white dots (`View.Layers`), above a plot of the probes.

## Interventions

The fields `food` and `trail` accept `Brush` strokes (assign, add, erase; clamped at zero) and
`Pulse` (adds to the trail; negative pulses are refused). `Disturb` is refused. In the Lab the
tools are **Paint food**, **Erase food** and **Paint trail**. Agents climb a painted food field
towards it and form a network between food sources.

## Checks

`tools/physarum_reference.py` is an independent numpy version of the same rules with its own
random numbers; the tests take the thresholds for coverage and structure count from it. A golden
digest fixes the exact Scala result, and the same run is compared on Ubuntu and macOS in CI. The
step does not allocate (under one byte per step, measured). A slow test checks the speed on
512 × 512.
