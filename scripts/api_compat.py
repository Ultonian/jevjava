"""Fail closed on missing compatibility baselines after the first release."""
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('jev-core', 'jev-test', 'jev-micrometer')


def compatibility_required(version, policy):
    current = version.removesuffix('-SNAPSHOT')
    baseline = policy['baseline']
    if baseline is None:
        if current != '0.1.0' or policy['initialVersion'] != '0.1.0':
            raise ValueError('Only the initial 0.1.0 release may have no API baseline')
        return None
    if not re.fullmatch(r'\d+\.\d+\.\d+', baseline):
        raise ValueError('API baseline must be an explicit released version')
    if not re.fullmatch(r'\d+\.\d+\.\d+', current):
        raise ValueError('Candidate must use major.minor.patch[-SNAPSHOT]')
    old, new = tuple(map(int, baseline.split('.'))), tuple(map(int, current.split('.')))
    if new < old:
        raise ValueError('API baseline is newer than the candidate')
    acceptance = policy.get('acceptedBreakingMinor')
    if acceptance is not None:
        if (new[0] != 0 or new[1] <= old[1] or new[2] != 0
                or acceptance != current or not policy.get('reason', '').strip()):
            raise ValueError('Breaking acceptance requires a named new 0.x minor and a reason')
        return False
    return True


def main():
    version = ET.parse(ROOT / 'pom.xml').getroot().findtext('{*}version')
    policy = json.loads((ROOT / 'config/quality/api-policy.json').read_text())
    required = compatibility_required(version, policy)
    if required is None:
        print('API compatibility: initial 0.1.0 only; no published baseline yet')
        return
    subprocess.run([str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress',
                    '-Papi-compat', '-pl', ','.join(MODULES), '-am',
                    '-Dapi.baseline=' + policy['baseline'],
                    '-Dapi.compatibility.required=' + str(required).lower(), 'verify'],
                   cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
