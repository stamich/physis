# Physis

[![CI](https://github.com/stamich/physis/actions/workflows/ci.yml/badge.svg)](https://github.com/stamich/physis/actions/workflows/ci.yml)

Physis is a Scala 3 framework for simulating and visualising models of natural
phenomena: ODE systems, numerical fields and agent-based models, with an emphasis
on verified numerics and reproducible runs.

The project is at milestone 0.1 (simulation core and ODE support). See
`docs/` for design notes as they are added.

## Build

Requirements: JDK 22 (Gradle can provision it automatically).

```bash
./gradlew build
```

## License

Apache License 2.0, see [LICENSE](LICENSE).
