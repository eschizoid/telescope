#!/usr/bin/env python3
"""Compare matching JMH JSON runs; optionally gate allocation and material latency regressions."""

import argparse
import json
import math
from pathlib import Path


def load(path):
    rows = json.loads(Path(path).read_text())
    return {
        (r["benchmark"], tuple(sorted(r.get("params", {}).items()))): r
        for r in rows
    }


def allocated(row):
    return next(
        metric["score"]
        for name, metric in row["secondaryMetrics"].items()
        if name.endswith("gc.alloc.rate.norm")
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    before, after = load(args.before), load(args.after)
    if before.keys() != after.keys():
        parser.error("Runs must contain exactly the same benchmark/parameter combinations")
    failed = []
    print("| Benchmark | Parameters | Before ns/op | After ns/op | Speedup | Before B/op | After B/op |")
    print("|---|---|---:|---:|---:|---:|---:|")
    for key, old in before.items():
        new = after[key]
        for setting in ("mode", "forks", "warmupIterations", "warmupTime", "measurementIterations", "measurementTime", "jvmArgs", "jdkVersion"):
            if old.get(setting) != new.get(setting):
                parser.error(f"Mismatched {setting} for {key}")
        a, b = old["primaryMetric"], new["primaryMetric"]
        if a["scoreUnit"] != "ns/op" or b["scoreUnit"] != "ns/op":
            parser.error("Expected average-time measurements in ns/op")
        av, bv = a["score"], b["score"]
        ab, bb = allocated(old), allocated(new)
        if not all(math.isfinite(v) for v in (av, bv, ab, bb)):
            parser.error(f"Non-finite measurement for {key}")
        label = key[0].split(".")[-2] + "." + key[0].split(".")[-1]
        params = ", ".join(f"{k}={v}" for k, v in key[1]) or "—"
        print(f"| {label} | {params} | {av:,.2f} | {bv:,.2f} | {av / bv:.2f}× | {ab:,.0f} | {bb:,.0f} |")
        # Timing gates require separated 99.9% confidence intervals AND a material delta.
        # The 2 ns absolute floor avoids treating sub-nanosecond wrapper variation as a regression.
        separated = b["scoreConfidence"][0] > a["scoreConfidence"][1]
        if separated and bv - av > max(2.0, av * 0.10):
            failed.append(f"latency regression: {label} ({params})")
        if bb - ab > max(8.0, ab * 0.05):
            failed.append(f"allocation regression: {label} ({params})")
        if "ContainerAllocationBenchmark" in key[0] and int(new["params"]["size"]) >= 16:
            if bb >= ab * 0.95:
                failed.append(f"less than 5% allocation improvement: {label} ({params})")
    if args.check and failed:
        parser.exit(1, "\n" + "\n".join(failed) + "\n")


if __name__ == "__main__":
    main()
