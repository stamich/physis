# Datasets: NetCDF export

Physis writes a run as a **NetCDF file**: the fields of the model at a series of times, the
probes, the time and a digest of the state at every record, with the manifest of the run inside
the file. `netCDF4` (that is, `libnetcdf`) and `scipy.io` read it, and so should any other reader
of classic NetCDF (xarray, MATLAB, Panoply, ParaView, the NCO tools; these were not tried).
`physis-cli export`, **File → Export data…** in the Lab and `sweep --export` write the
same files.

```bash
physis-cli export sea.nc --model shallow --param nx=48 --param ny=32 --param start=1 --steps 40 --every 10
physis-cli verify sea.nc
```

```python
import xarray as xr
sea = xr.open_dataset("sea.nc")
sea.height.isel(time=-1).plot()      # the sea surface after the last record
sea.nusselt                          # a probe is a series over time
```

## What is in the file

| Variable | Dimensions | Meaning |
| --- | --- | --- |
| `time` | `time` | simulated time, in the model's unit (`s` for the shallow sea) |
| `step` | `time` | steps taken |
| `digest_hi`, `digest_lo` | `time` | the 64-bit digest of the state (`Run.digest`) as two 32-bit integers |
| `x`, `y` | `x`, `y` | centres of the cells, in the model's length unit (`y` runs south for the shallow sea: `positive = "down"`) |
| the fields | `time`, `y`, `x` | one variable per field of the model, 64-bit floats (`--float`: 32-bit) |
| the probes | `time` | one variable per probe of the model, 64-bit floats |

`time` is the unlimited (record) dimension. The number of records is written in the header, which
is why the file is made in one pass and the size is known before the run. Models without
fields (Lorenz, the double pendulum and Boids) have only the probes.

| Model | Fields |
| --- | --- |
| Gray–Scott | `u`, `v` |
| Waves | `u`, `wall` |
| Physarum | `trail`, `food` |
| Fluids | `dye`, `vorticity`, `speed`, `wall` |
| Convection | `temperature`, `deviation`, `vorticity`, `speed`, `wall` |
| Shallow water | `height`, `speed`, `vorticity`, `depth`, `land` |

The variables carry `long_name`, `units` and, where CF has one, `standard_name` (the shallow
sea's `height`, `speed` and `depth`). The global attributes are `Conventions = "CF-1.8"`, `title`,
`source` (`Physis` and its version), `physis_model`, `physis_model_version`, `physis_steps`,
`physis_every`, `physis_precision` and `physis_manifest`, the manifest of the run as JSON.

## Records

The first record is the state before any step; then one after every `--every` steps (the Lab
asks the same). Without `--every` the CLI writes about eleven records. A run of 40 steps with
`--every 10` has five records, at steps 0, 10, 20, 30 and 40; steps the run does not reach a
multiple of `every` away are not written. The CLI announces the size of the file before the run
and checks it afterwards: it is known to the byte from the grid, the number of records and the
precision. A 128 × 96 sea with five fields and 201 records is about 100 MB; `--float` halves it,
`--vars height,speed` writes only those.

## Checking a file

`physis-cli verify sea.nc` reads the manifest, `physis_every`, the precision and the variable
choice out of the file, runs the model again and compares **every value, bit for bit**: the
fields, the probes, the time and the digests. The attributes are not compared, since they hold the
version of the program that wrote the file. It exits with 0 if the data agrees and 1 if not,
naming the first variable, record and value that differs.

`physis-cli replay run.json --export again.nc --every 10` writes the dataset of a manifest, with
its strokes and parameter changes at the steps they were made.

## Sweeps

`sweep --export <dir>` writes `point-0000.nc`, `point-0001.nc`, … for the points that finished, in
grid order. Each file is the replay of the manifest of its point, with the same `--every`,
`--vars` and `--float`, and the replay is checked against the digest the sweep got. See
[sweeps.md](sweeps.md).

## The format and how it was checked

Physis writes the **classic NetCDF format with 64-bit offsets** (CDF-2, `CDF\002`) with its own
code: no native library, no HDF5, nothing but the JDK. It stores numbers big endian, pads each
item to four bytes, and writes the fixed variables (`x`, `y`) before the records, with the
variables of one record next to each other. The header is written first, so the number of records
is known then; this is what lets a record be written and forgotten.

- `physis.netcdf.NcWriter` writes a schema and its records; `NcExport` plans and writes a run;
  `NcFile` reads a file back (CDF-1 and CDF-2, `int`, `float` and `double` variables, text and
  numeric attributes); `NcCompare` compares two files bit for bit.
- `tools/netcdf_reference.py` is a second writer and reader, written separately in Python. The
  tests compare the bytes of small files with its output, and it has been checked against
  `libnetcdf` (`netCDF4`) and `scipy.io.netcdf_file`: the same dimensions, attributes and values
  came out. That check is run by hand, not in CI, since it needs those packages.
- Single precision rounds to the nearest `float`; an `int` variable must hold a whole number.

## Limits

- The file is classic NetCDF, not NetCDF-4: no compression, no groups, no chunking. A reader of
  NetCDF-4 reads it too.
- Variables of one file share one grid (`x`, `y`); a model with fields of different sizes is
  refused.
- The reader refuses files over 2 GB; the Lab writes at most 1 GB, and says which `every` would
  fit.
- The Lab and the CLI export fixed-step models only. All the models of Physis are.
