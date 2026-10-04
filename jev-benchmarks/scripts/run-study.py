#!/usr/bin/env python3
"""Compatibility entry point; implementation lives in run_study.py."""
import sys
from pathlib import Path

# Resolve sibling modules from this entry point, including with Python safe-path mode.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_study import main  # noqa: E402

if __name__ == "__main__":
    main()
