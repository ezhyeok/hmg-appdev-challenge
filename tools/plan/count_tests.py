"""Count passed tests from JUnit XML results and write docs/plan/data/test_counts.json.

The planning doc and the hero figure read the counts from that file, so a number in the document
is always a number that was measured (a test count typed by hand went stale three times on
2026-11-11). The script fails when a module has no results or any failures, so the document is
never built from a red or missing run.

Run after the tests:
  ./gradlew -p rest-engine test                      (engine + coach, on the server)
  laptop: tools/laptop/gradle.ps1 :app:testDebugUnitTest
  python3 -I tools/plan/count_tests.py --fetch-app [repo-folder]   (copies the app and pleos-kit XML from the laptop)
"""
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
LAPTOP_LOCAL = os.path.join(ROOT, 'build', 'laptop-test-results')
MODULES = {
    'engine': os.path.join(ROOT, 'rest-engine', 'build', 'test-results', 'test'),
    'coach': os.path.join(ROOT, 'rest-engine', 'coach', 'build', 'test-results', 'test'),
    # Android modules run on the laptop (tools/laptop/gradle.ps1 :app:testDebugUnitTest :pleos-kit:testDebugUnitTest)
    'app': os.path.join(LAPTOP_LOCAL, 'app'),
    'kit': os.path.join(LAPTOP_LOCAL, 'pleos-kit'),
}


def fetch_app(repo='repo'):
    """Copy the Android modules' JUnit XML from the laptop folder that ran them (default repo\).

    Each module is copied into a fresh folder first and replaces the local copy only when the copy worked: the
    old version deleted the local results before copying, so a failed scp (laptop offline, 2026-11-13) left no
    results at all and the counts could not be written until the laptop came back.
    """
    import shutil
    import tempfile
    for module in ('app', 'pleos-kit'):
        local = os.path.join(LAPTOP_LOCAL, module)
        os.makedirs(LAPTOP_LOCAL, exist_ok=True)
        remote = f'hmg-laptop:hmg-appdev-challenge/{repo}/{module}/build/test-results/testDebugUnitTest/*.xml'
        fresh = tempfile.mkdtemp(prefix=module + '-', dir=LAPTOP_LOCAL)
        try:
            subprocess.run(['scp', '-q', remote, fresh], check=True)
            if not glob.glob(os.path.join(fresh, 'TEST-*.xml')):
                raise SystemExit(f'no test results copied for {module}')
            shutil.rmtree(local, ignore_errors=True)
            os.replace(fresh, local)
        finally:
            shutil.rmtree(fresh, ignore_errors=True)


# Test sources per module. A filtered run (gradlew ... --tests X) replaces the results with only the classes it ran, so
# the counts must come from a run of the whole module: the number of result suites has to cover the test classes.
# (2026-11-13: a voice-only run left 3 coach suites and the counts read 26 coach tests instead of 628.)
SOURCES = {
    'engine': os.path.join(ROOT, 'rest-engine', 'src', 'test'),
    'coach': os.path.join(ROOT, 'rest-engine', 'coach', 'src', 'test'),
    'app': os.path.join(ROOT, 'app', 'src', 'test'),
    'kit': os.path.join(ROOT, 'pleos-kit', 'src', 'test'),
}


def test_classes(module):
    """Source files that declare at least one @Test (one result suite each)."""
    n = 0
    for f in glob.glob(os.path.join(SOURCES[module], '**', '*.kt'), recursive=True):
        with open(f, encoding='utf-8') as fh:
            if '@Test' in fh.read():
                n += 1
    return n


def count(directory, module=None):
    files = glob.glob(os.path.join(directory, 'TEST-*.xml'))
    if not files:
        raise SystemExit(f'no test results in {directory}')
    if module is not None:
        expected = test_classes(module)
        if len(files) < expected:
            raise SystemExit(f'{module}: results look partial ({len(files)} suites, {expected} test classes in the sources). '
                             f'Run the whole module, not a filtered --tests run.')
    total = failures = skipped = 0
    for f in files:
        suite = ET.parse(f).getroot()
        total += int(suite.get('tests', 0))
        failures += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
        skipped += int(suite.get('skipped', 0))
    return {'tests': total, 'failures': failures, 'skipped': skipped, 'suites': len(files)}


def main():
    if '--fetch-app' in sys.argv:
        i = sys.argv.index('--fetch-app')
        fetch_app(sys.argv[i + 1] if len(sys.argv) > i + 1 and not sys.argv[i + 1].startswith('-') else 'repo')
    out = {name: count(d, name) for name, d in MODULES.items()}
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
