// The root project only aggregates; every module applies the
// `physis.scala-conventions` plugin from build-logic.
//
// Common entry points:
//   ./gradlew build          compile, unit tests, formatting check
//   ./gradlew slowTest       long-running validation tests (nightly in CI)
//   ./gradlew spotlessApply  reformat sources with scalafmt
