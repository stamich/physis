#!/usr/bin/env python3
"""An independent implementation of the classic NetCDF format (CDF-2), to check Physis.

It shares no code with the Scala writer in modules/netcdf. Commands:

  netcdf_reference.py mini          hex of the small file that NcWriterSuite compares with
  netcdf_reference.py full          hex of the file with every kind of variable (NcWriterSuite)
  netcdf_reference.py check FILE    read FILE with this script's own parser and, when the
                                    packages are installed, with scipy and netCDF4 too; every
                                    value must agree. Exit 1 if not.

The format: 'CDF' 0x02, the number of records, the lists of dimensions, global attributes and
variables, then the variables without a record dimension, then the records, all big endian.
"""
import struct
import sys

NC_DIM, NC_VAR, NC_ATT = 10, 11, 12
CHAR, INT, FLOAT, DOUBLE = 2, 4, 5, 6
SIZE = {INT: 4, FLOAT: 4, DOUBLE: 8}
FMT = {INT: ">i", FLOAT: ">f", DOUBLE: ">d"}


def pad(b):
    return b + b"\0" * ((-len(b)) % 4)


def name(s):
    b = s.encode()
    return struct.pack(">i", len(b)) + pad(b)


def attr(n, kind, v):
    if kind == CHAR:
        b = v.encode()
        return name(n) + struct.pack(">ii", CHAR, len(b)) + pad(b)
    vals = v if isinstance(v, list) else [v]
    return name(n) + struct.pack(">ii", kind, len(vals)) + b"".join(struct.pack(FMT[kind], x) for x in vals)


def attr_list(atts):
    if not atts:
        return struct.pack(">ii", 0, 0)
    return struct.pack(">ii", NC_ATT, len(atts)) + b"".join(attr(*a) for a in atts)


def build(dims, gatts, variables, records, fixed, record_data):
    """dims: [(name, size or 0 for the record dimension)];
    variables: [(name, [dimension index], [attributes], type)];
    fixed: {name: [values]}; record_data: one {name: [values]} per record."""
    def is_record(v):
        return bool(v[1]) and dims[v[1][0]][1] == 0

    def count(v):
        n = 1
        for i in (v[1][1:] if is_record(v) else v[1]):
            n *= dims[i][1]
        return n

    def header(begins):
        h = b"CDF\x02" + struct.pack(">i", records)
        h += struct.pack(">ii", NC_DIM, len(dims)) if dims else struct.pack(">ii", 0, 0)
        h += b"".join(name(n) + struct.pack(">i", s) for n, s in dims)
        h += attr_list(gatts)
        h += struct.pack(">ii", NC_VAR, len(variables)) if variables else struct.pack(">ii", 0, 0)
        for v, begin in zip(variables, begins):
            n, ids, atts, t = v
            h += name(n) + struct.pack(">i", len(ids)) + b"".join(struct.pack(">i", i) for i in ids)
            h += attr_list(atts) + struct.pack(">i", t) + struct.pack(">I", count(v) * SIZE[t])
            h += struct.pack(">q", begin)
        return h

    start = len(header([0] * len(variables)))
    begins, at, inrec = [], start, 0
    fixed_bytes = sum(count(v) * SIZE[v[3]] for v in variables if not is_record(v))
    rec_start = start + fixed_bytes
    for v in variables:
        if is_record(v):
            begins.append(rec_start + inrec)
            inrec += count(v) * SIZE[v[3]]
        else:
            begins.append(at)
            at += count(v) * SIZE[v[3]]
    out = header(begins)
    for v in variables:
        if not is_record(v):
            out += b"".join(struct.pack(FMT[v[3]], x) for x in fixed[v[0]])
    for r in range(records):
        for v in variables:
            if is_record(v):
                out += b"".join(struct.pack(FMT[v[3]], x) for x in record_data[r][v[0]])
    return out


def mini():
    return build(
        [("time", 0), ("x", 2)],
        [("title", CHAR, "hi")],
        [("x", [1], [("units", CHAR, "m")], DOUBLE), ("v", [0, 1], [("scale", DOUBLE, 0.5)], FLOAT)],
        2,
        {"x": [1.5, 2.5]},
        [{"v": [r + 0.25, r + 0.75]} for r in range(2)],
    )


def full():
    """The file of NcReaderSuite with three records."""
    dims = [("time", 0), ("y", 2), ("x", 3)]
    gatts = [("Conventions", CHAR, "CF-1.8"), ("title", CHAR, "a run"), ("steps", INT, 7), ("dt", DOUBLE, 0.25)]
    variables = [
        ("time", [0], [("axis", CHAR, "T"), ("long_name", CHAR, "time"), ("units", CHAR, "s")], DOUBLE),
        ("x", [2], [("axis", CHAR, "X"), ("long_name", CHAR, "x"), ("units", CHAR, "m")], DOUBLE),
        ("n", [0], [], INT),
        ("f", [0, 1, 2], [("long_name", CHAR, "a field"), ("units", CHAR, "m")], FLOAT),
        ("g", [0, 1, 2], [("long_name", CHAR, "another field"), ("units", CHAR, "1")], DOUBLE),
    ]
    records = [
        {
            "time": [r * 10.0],
            "n": [r],
            "f": [r + i * 0.5 for i in range(6)],
            "g": [r * 100.0 + i / 3.0 for i in range(6)],
        }
        for r in range(3)
    ]
    return build(dims, gatts, variables, 3, {"x": [0.5, 1.5, 2.5]}, records)


def parse(data):
    """A minimal reader: {'dims', 'atts', 'vars': {name: (type, shape, values)}}."""
    at = [0]

    def i32():
        x = struct.unpack_from(">i", data, at[0])[0]
        at[0] += 4
        return x

    def nm():
        n = i32()
        s = data[at[0]:at[0] + n].decode()
        at[0] += (n + 3) // 4 * 4
        return s

    def atts():
        tag, n = i32(), i32()
        out = {}
        for _ in range(n):
            key, kind, cnt = nm(), i32(), i32()
            if kind == CHAR:
                out[key] = data[at[0]:at[0] + cnt].decode()
                at[0] += (cnt + 3) // 4 * 4
            else:
                out[key] = [struct.unpack_from(FMT[kind], data, at[0] + SIZE[kind] * k)[0] for k in range(cnt)]
                at[0] += SIZE[kind] * cnt
        return out

    assert data[:3] == b"CDF" and data[3] == 2, "not a CDF-2 file"
    at[0] = 4
    records = i32()
    tag, n = i32(), i32()
    dims = [(nm(), i32()) for _ in range(n)]
    gatts = atts()
    tag, n = i32(), i32()
    raw = []
    for _ in range(n):
        key = nm()
        ids = [i32() for _ in range(i32())]
        vatts = atts()
        kind, vsize = i32(), struct.unpack_from(">I", data, at[0])[0]
        at[0] += 4
        begin = struct.unpack_from(">q", data, at[0])[0]
        at[0] += 8
        raw.append((key, ids, vatts, kind, begin))
    def per_record(ids):
        n = 1
        for i in ids:
            n *= dims[i][1]
        return n

    recsize = sum(
        SIZE[k] * per_record(ids[1:]) for _, ids, _, k, _ in raw if ids and dims[ids[0]][1] == 0
    )
    out = {}
    for key, ids, vatts, kind, begin in raw:
        rec = bool(ids) and dims[ids[0]][1] == 0
        inner = ids[1:] if rec else ids
        per = 1
        for i in inner:
            per *= dims[i][1]
        vals = []
        for r in range(records if rec else 1):
            base = begin + (r * recsize if rec else 0)
            vals += [struct.unpack_from(FMT[kind], data, base + SIZE[kind] * k)[0] for k in range(per)]
        shape = ([records] if rec else []) + [dims[i][1] for i in inner]
        out[key] = (kind, shape, vals, vatts)
    return {"dims": dims, "atts": gatts, "vars": out, "records": records}


def check(path):
    data = open(path, "rb").read()
    mine = parse(data)
    print(f"{path}: {mine['records']} records, dimensions {mine['dims']}")
    for key, (kind, shape, vals, vatts) in mine["vars"].items():
        print(f"  {key} {shape} type {kind}: {len(vals)} values, attributes {sorted(vatts)}")
    ok = True
    try:
        import numpy as np
        from scipy.io import netcdf_file
        with netcdf_file(path, "r", mmap=False) as f:
            for key, (kind, shape, vals, _) in mine["vars"].items():
                theirs = np.array(f.variables[key][:]).ravel()
                same = np.array_equal(theirs, np.array(vals), equal_nan=True) if kind != FLOAT else np.allclose(theirs, np.array(vals, dtype=np.float32))
                print(f"  scipy {key}: {'same' if same else 'DIFFERENT'}")
                ok &= bool(same)
    except ImportError:
        print("  scipy is not installed; skipped")
    try:
        import netCDF4
        import numpy as np
        with netCDF4.Dataset(path) as d:
            print("  netCDF4:", d.data_model)
            for key, (kind, shape, vals, _) in mine["vars"].items():
                d[key].set_auto_maskandscale(False)
                theirs = np.array(d[key][:]).ravel()
                same = np.array_equal(theirs, np.array(vals, dtype=theirs.dtype), equal_nan=True)
                print(f"  netCDF4 {key}: {'same' if same else 'DIFFERENT'}")
                ok &= bool(same)
    except ImportError:
        print("  netCDF4 is not installed; skipped")
    return 0 if ok else 1


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "mini":
        print(mini().hex())
    elif cmd == "full":
        print(full().hex())
    elif cmd == "check" and len(sys.argv) == 3:
        sys.exit(check(sys.argv[2]))
    else:
        print(__doc__)
        sys.exit(2)
