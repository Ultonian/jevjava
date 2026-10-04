#!/usr/bin/env python3
"""Compatibility entry point; implementation lives in executor_pilot.py."""
import sys
from pathlib import Path

# Resolve sibling modules from this entry point, including with Python safe-path mode.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from executor_pilot import main  # noqa: E402

if __name__ == "__main__":
    main()
