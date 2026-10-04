"""Shared Linux telemetry, artifact identity and process lifecycle for fixed study protocols."""
import argparse
import hashlib
import json
import os
import signal
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def stamp():
    return datetime.now(timezone.utc).isoformat()


def read_optional(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return "unavailable"


def host(cpus):
    policies = {}
    for cpu in cpus:
        root = Path(f"/sys/devices/system/cpu/cpu{cpu}/cpufreq")
        policies[str(cpu)] = {name: read_optional(root / name) for name in (
            "scaling_driver", "scaling_governor", "scaling_min_freq", "scaling_max_freq",
            "scaling_cur_freq", "energy_performance_preference")}
    counters = {}
    for line in Path("/proc/stat").read_text().splitlines():
        fields = line.split()
        if fields and fields[0].startswith("cpu"):
            # Exclude guest columns, which are already included in user/nice.
            counters[fields[0]] = [int(x) for x in fields[1:9]]
    return {"at": stamp(), "loadAverage": os.getloadavg(), "cpuCounters": counters,
            "frequencyPolicies": policies,
            "platformProfile": read_optional("/sys/firmware/acpi/platform_profile"),
            "boost": read_optional("/sys/devices/system/cpu/cpufreq/boost"),
            "pstateStatus": read_optional("/sys/devices/system/cpu/amd_pstate/status"),
            "thermalMilliC": {str(p.parent.name): read_optional(p)
                              for p in Path("/sys/class/thermal").glob("thermal_zone*/temp")},
            "powerOnline": {p.parent.name: read_optional(p)
                            for p in Path("/sys/class/power_supply").glob("*/online")}}


def process_affinities(parent):
    pending, seen, observed = [parent], set(), {}
    while pending:
        pid = pending.pop()
        if pid in seen:
            continue
        seen.add(pid)
        try:
            observed[str(pid)] = sorted(os.sched_getaffinity(pid))
            for task in Path(f"/proc/{pid}/task").glob("*/children"):
                try:
                    pending.extend(int(p) for p in task.read_text().split())
                except FileNotFoundError:
                    pass
        except ProcessLookupError:
            pass
    return observed


def stop(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
    except ProcessLookupError:
        pass
    # Also remove descendants left behind by a failed parent.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def interrupted(signum, frame):
    raise InterruptedError(f"Study interrupted by signal {signum}")


def require_linux():
    if sys.platform != "linux" or not hasattr(os, "sched_getaffinity"):
        raise ValueError("Study execution requires Linux with CPU affinity support; archive audit is portable")


def parse_cpus(value):
    try:
        cpus = [int(part) for part in value.split(",")]
    except ValueError as exc:
        raise argparse.ArgumentTypeError("CPU IDs must be comma-separated integers") from exc
    if len(cpus) != 8 or len(set(cpus)) != 8 or any(cpu < 0 for cpu in cpus):
        raise argparse.ArgumentTypeError("Select eight distinct non-negative CPU IDs")
    return cpus
