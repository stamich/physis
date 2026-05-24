plugins {
    id("physis.scala-conventions")
}

description = "Physis NetCDF: a writer and a reader for the classic file format (CDF-2), with CF metadata for fields and time series (no UI, no native library)."

dependencies {
    api(project(":core"))
    testImplementation(project(":verify"))
}
