package physis.models.convection

import physis.core.Stepping
import physis.core.run.{Physis, Run}

/** Helpers shared by the convection suites. */
object ConvectionSupport:
  type ConvectionRun = Run[ConvectionParams, ConvectionState]

  def run(p: ConvectionParams, stepping: Stepping = Convection.stepping): ConvectionRun =
    Physis.model(Convection).withParams(p).stepping(stepping).probe(Convection.probes*).build()

  def params(
      rayleigh: Double = 4000.0,
      prandtl: Double = 1.0,
      sides: Int = ConvectionSides.Periodic,
      nx: Int = 48,
      ny: Int = 24,
      kick: Double = 0.01
  ): ConvectionParams = ConvectionParams(rayleigh, prandtl, sides, nx, ny, kick)
