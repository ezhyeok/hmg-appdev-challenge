"""Count passed tests from JUnit XML results and write docs/plan/data/test_counts.json.

The planning doc and the hero figure read the counts from that file, so a number in the document
is always a number that was measured (a test count typed by hand went stale three times on
2026-11-11). The script fails when a module has no results or any failures, so the document is
never built from a red or missing run.

Run after the tests:
  ./gradlew -p rest-engine test                      (engine + coach, on the server)
  laptop: tools/laptop/gradle.ps1 :app:testDebugUnitTest
  python3 -I tools/plan/count_tests.py --fetch-app   (copies the app XML from the laptop)
"""
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
APP_LOCAL = os.path.join(ROOT, 'build', 'laptop-test-results', 'app')
MODULES = {
    'engine': os.path.join(ROOT, 'rest-engine', 'build', 'test-results', 'test'),
    'coach': os.path.join(ROOT, 'rest-engine', 'coach', 'build', 'test-results', 'test'),
    'app': APP_LOCAL,
}


def fetch_app(repo='repo'):
    os.makedirs(APP_LOCAL, exist_ok=True)
    for f in glob.glob(os.path.join(APP_LOCAL, '*.xml')):
        os.remove(f)
    remote = f'hmg-laptop:hmg-appdev-challenge/{repo}/app/build/test-results/testDebugUnitTest/*.xml'
    subprocess.run(['scp', '-q', remote, APP_LOCAL], check=True)


def count(directory):
    files = glob.glob(os.path.join(directory, 'TEST-*.xml'))
    if not files:
        raise SystemExit(f'no test results in {directory}')
    total = failures = skipped = 0
    for f in files:
        suite = ET.parse(f).getroot()
        total += int(suite.get('tests', 0))
        failures += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
        skipped += int(suite.get('skipped', 0))
    return {'tests': total, 'failures': failures, 'skipped': skipped, 'suites': len(files)}


def main():
    if '--fetch-app' in sys.argv:
        fetch_app()
    out = {name: count(d) for name, d in MODULES.items()}
    bad = {k: v for k, v in out.items() if v['failures']}
    if bad:
        raise SystemExit(f'failing tests, not writing counts: {bad}')
    out['passed'] = sum(v['tests'] - v['skipped'] for k, v in out.items() if isinstance(v, dict))
    out['commit'] = subprocess.run(['git', '-C', ROOT, 'rev-parse', '--short', 'HEAD'], capture_output=True,
                                   text=True).stdout.strip()
    path = os.path.join(ROOT, 'docs', 'plan', 'data', 'test_counts.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(json.dumps(out, ensure_ascii=False))


if __name__ == '__main__':
    main()
