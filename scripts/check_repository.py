"""Small offline repository checks shared by CI and pre-commit."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
for command in ([sys.executable, '-B', 'scripts/check_links.py'],
                [sys.executable, '-B', 'scripts/monitor.py', 'pins'],
                [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', 'scripts', '-v']):
    subprocess.run(command, cwd=ROOT, check=True)
