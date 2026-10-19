# Interventions

An intervention is an edit of the state of a running model: a brush dab that sets or adds to
some cells, a pulse dropped on a wave, a disturbance of the boids inside a circle. Interventions
are part of the run, not something done to it from outside: each one happens between two steps,
is recorded with the step it came before, and is applied again by a replay. A session with
strokes replays bit for bit, like one with parameter changes.

## What an intervention is

An intervention stores a shape and an operation, never cells, so a stroke takes tens of bytes in
a manifest.

| Intervention | Fields | Meaning |
| --- | --- | --- |
| `Brush` | target slice, `Disc(x, y, r)`, `Op`, value | `Assign` sets the cells in the disc to the value, `Add` adds it, `Erase` sets them to zero |
| `Pulse` | target slice, x, y, sigma, amplitude | adds a Gaussian bump (reach 6 sigma) |
| `Disturb` | `Disc(x, y, r)`, strength | turns the agents inside the disc towards the direction away from its centre by `strength` radians (exactly onto it when closer) |

Coordinates are in cells, may be fractional, and use the cell-centre convention: cell `(i, j)`
has its centre at `(i + 0.5, j + 0.5)`. A cell is inside a disc when its centre is within `r`
of the disc's centre. Periodic grids use the nearest image, so a disc across the seam paints on
both sides, and every cell is visited once.

A model decides what it accepts:

- `Model.accepts(iv)` says yes or gives a reason; the default refuses everything, so a model
  that knows nothing about interventions says so instead of ignoring them;
- `Model.intervene(state, params, iv)` applies one, in place, without allocating.

| Model | Accepts |
| --- | --- |
| Gray–Scott | `Brush` on `u` or `v`; values are clamped to [0, 1] |
| Waves | `Brush` on `wall` (0 or 1) and on `u`; `Pulse` on `u` |
| Boids | `Disturb` with a strength in (0, 2] |
| Lorenz, double pendulum | none |

A refused intervention is not recorded; the reason appears in the Lab's status line.

## Order and timing

- `Run.intervene` queues the edit; it is applied immediately before the next step, after the
  parameter changes of that step, in the order it was sent. A paused run applies it at the next
  single step or when it resumes.
- Each applied edit becomes an `InterventionEvent(step, intervention)`.
- Reset and Restart clear the log with the rest of the run. Interventions that belong to the
  definition (a preset's walls) are given to the builder and come back with Reset.
- `LiveRun` takes `Command.Intervene(iv)`. The model checks it when the command is read.
  `Command.Restart(values, seed, painted)` can carry the interventions of the new definition.

## Manifest format 3

Manifests gain an `interventions` array; `format` is 3 when it is not empty and 2 otherwise, so
every manifest written before 0.4 reads unchanged and sessions without strokes still write the
older format.

```json
{
  "kind": "brush", "step": 120, "target": "wall",
  "x": 120.5, "y": 100.5, "r": 5.0, "op": "set", "value": 1.0
}
```

`Manifest.replay` applies the events before the same steps; the final digest tells whether the
replay reproduced the session.

## In the Lab

Models with interventions show a tool box (key **B** switches the tool on or off): tool buttons,
a radius slider, the tool's value, and a counter of the strokes recorded. The mouse wheel over
the view changes the radius; a ring shows the brush. With a tool on, the left button applies it
and the right button erases; with the tool off the pointer only reads cells. A drag is turned
into dabs at most half a radius apart and sent once per frame. See [lab.md](lab.md).
