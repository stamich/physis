"""Reference solution of the Lorenz system for physis golden tests.

Integrates sigma = 10, rho = 28, beta = 8/3 from (1, 1, 1) to t = 1 with
mpmath's arbitrary-precision Taylor-series ODE solver at 40 significant
digits, and prints the state as CSV (t,x,y,z) with 17 significant digits.

Usage:
    pip install mpmath
    python tools/lorenz_reference.py > models/lorenz/src/test/resources/lorenz-t1.csv

Regenerating the golden file is a deliberate act: review the diff.
"""

from mpmath import mp, mpf, odefun

mp.dps = 40

SIGMA = mpf(10)
RHO = mpf(28)
BETA = mpf(8) / 3


def lorenz(t, state):
    x, y, z = state
    return [SIGMA * (y - x), x * (RHO - z) - y, x * y - BETA * z]


def main() -> None:
    solution = odefun(lorenz, 0, [mpf(1), mpf(1), mpf(1)])
    x, y, z = solution(1)
    print("t,x,y,z")
    print(",".join(mp.nstr(v, 17, min_fixed=-30, max_fixed=30) for v in (mpf(1), x, y, z)))


if __name__ == "__main__":
    main()
