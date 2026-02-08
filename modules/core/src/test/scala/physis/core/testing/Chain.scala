package physis.core.testing

import physis.core.{Digest, Model, StepContext}
import physis.core.param.{ParamSchema, ParamSpec}
import physis.core.random.SplittableRng

/** Test model whose state size is a restart-only parameter. */
final case class ChainParams(n: Int, rate: Double)

object ChainParams:
  given schema: ParamSchema[ChainParams] = ParamSchema.derived[ChainParams](
    ParamSpec("n", default = 4, min = 1, max = 1000, doc = "cells", restart = true),
    ParamSpec("rate", default = 1.0, min = 0.0, max = 100.0)
  )

object Chain extends Model[ChainParams]:
  type State = Array[Double]
  val id = "chain"
  val version = "1"
  val params: ParamSchema[ChainParams] = ChainParams.schema

  def init(p: ChainParams, rng: SplittableRng): Array[Double] =
    Array.tabulate(p.n)(i => rng.fork(i.toLong).nextDouble())

  def advance(state: Array[Double], p: ChainParams, dt: Double, ctx: StepContext): Unit =
    var i = 0
    while i < state.length do
      state(i) += p.rate * dt * (i + 1)
      i += 1
    ctx.taken = dt
    ctx.suggestedNext = dt

  def digest(state: Array[Double]): Long = Digest.ofDoubles(state)
