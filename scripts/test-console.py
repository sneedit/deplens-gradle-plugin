#!/usr/bin/env python3
"""Exercise the actual Gradle console in a POSIX PTY using an isolated local build.

Usage: JAVA_HOME=... python3 scripts/test-console.py [path/to/gradle] [extra Gradle flags...]
Requires Python 3 and macOS/Linux. No third-party Python packages are needed.
"""
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import struct
import subprocess
import sys
import tempfile
import termios
import time
import zipfile

PLUGIN = Path(__file__).resolve().parents[1]
GRADLE = str(Path(sys.argv[1]).resolve()) if len(sys.argv) > 1 else str(PLUGIN / "gradlew")
EXTRA_GRADLE_ARGS = sys.argv[2:]
CACHE_ENABLED = '--configuration-cache' in EXTRA_GRADLE_ARGS
# Hold the controlling session alive until terminal modes have been inspected.
HELPER = """
import fcntl, os, pathlib, subprocess, sys, termios
os.setsid()
fcntl.ioctl(0, termios.TIOCSCTTY, 0)
before = termios.tcgetattr(0)
# Exercise the ordinary Gradle invocation with inherited stdin and automatic console mode.
result = subprocess.run(sys.argv[1:])
after = termios.tcgetattr(0)
# macOS sets this transient flag when canonical mode is restored.
before[3] &= ~getattr(termios, 'PENDIN', 0)
after[3] &= ~getattr(termios, 'PENDIN', 0)
pathlib.Path('terminal-restored').write_text(str(before == after))
sys.exit(result.returncode)
"""


def exercise(root, scenario, keys, expected, wrapper_expected=None, failure=False):
    master, slave = pty.openpty()
    os.set_blocking(master, False)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 32, 180, 0, 0))
    environment = dict(os.environ)
    environment.pop("DEPLENS_TTY", None)
    environment["TERM"] = "xterm-256color"
    process = subprocess.Popen(
        [sys.executable, "-c", HELPER, GRADLE, "depLensInteractiveUpdate", "--offline", "-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME", *EXTRA_GRADLE_ARGS],
        cwd=root, env=environment, stdin=slave, stdout=slave, stderr=slave,
    )
    output, sent = bytearray(), False
    deadline = time.monotonic() + 180
    try:
        while process.poll() is None and time.monotonic() < deadline:
            if select.select([master], [], [], 0.2)[0]:
                try:
                    output.extend(os.read(master, 65536))
                except (BlockingIOError, OSError):
                    continue
                if b"Choose updates to apply" in output and not sent:
                    time.sleep(0.2)
                    os.write(master, keys)
                    sent = True
        if process.poll() is None:
            process.kill()
            process.wait()
            raise AssertionError("Terminal test timed out")
        # Collect buffered tail output without waiting indefinitely on the PTY.
        tail_deadline = time.monotonic() + 0.5
        while time.monotonic() < tail_deadline:
            try:
                output.extend(os.read(master, 65536))
            except (BlockingIOError, OSError):
                pass
            time.sleep(0.01)
        log = output.decode(errors="replace")
        (root / (scenario + ".log")).write_text(log)
        assert (process.returncode != 0 if failure else process.returncode == 0), log[-4000:]
        if failure:
            assert "Invalid wrapper distribution checksum" in log, log[-4000:]
        assert sent, "No selection screen appeared\n" + log[-8000:]
        assert (root / "terminal-restored").read_text() == "True", "Terminal modes changed"
        assert 'shared="' + expected + '"' in (root / "gradle/libs.versions.toml").read_text()
        assert "g:unused" in log, "Unused shared alias was not displayed"
        assert "g:hidden" not in log, "Output-filtered dependency appeared in interactive output"
        # The alternate screen disappears on Enter/Escape; inspect what remains in scrollback.
        completion = log.split("\x1b[?1049l", 1)[-1]
        assert "Selection " not in log and "Catalog aliases:" not in log, "Internal selection dump in normal output"
        assert "recommended updates" not in log, "Check listing duplicated the interactive selector"
        assert "affects [" not in completion, "Consumer dump in completion output"
        if not failure:
            if wrapper_expected is not None:
                assert "applied 2 version update(s) in 2 file(s)" in completion, completion
                assert "nested/gradle/wrapper/gradle-wrapper.properties" in completion, completion
                assert "8.8 → " + wrapper_expected in completion, completion
            elif scenario == "apply":
                assert "applied 1 version update(s) in 1 file(s)" in completion, completion
                assert "gradle/libs.versions.toml" in completion, completion
                assert "versions.shared: 1.0 → 1.1" in completion, completion
                summary = completion.split("DepLens: applied", 1)[1].split("Report:", 1)[0]
                assert str(root) not in summary, completion
            elif scenario == "dynamic-bulk-current":
                assert "No updates selected; no sources changed." in completion, completion
            else:
                assert "Update selection cancelled; no sources changed." in completion, completion
        report = json.loads((root / "build/reports/deplens/depLensInteractiveUpdate.json").read_text())
        assert report['complete'] and report['checksComplete'], 'Incomplete interactive analysis'
        contexts = [e for e in report['entries'] if e['coordinate'] == 'g:context']
        assert {(Path(e['build']).resolve().relative_to(root.resolve()).as_posix(), e['project']) for e in contexts} == {
            ('.', ':sub'), ('buildSrc', ':'), ('nested', ':')}, 'Missing composite consumer contexts'
        if wrapper_expected is not None:
            assert len(report["selectedSources"]) == 2, "Both wrapper sources must be recorded"
            assert all(source['source']['kind'] == 'WRAPPER' for source in report['selectedSources'])
            wrappers = [e for e in report['entries'] if e['coordinate'] == 'gradle' and e.get('selectedVersion')]
            assert len(wrappers) == 2 and all(e['selectedVersion'] == wrapper_expected for e in wrappers)
        elif scenario == "apply":
            assert len(report["selectedSources"]) == 1, "Shared source must be changed once"
            consumers = [e for e in report["entries"] if e["coordinate"] in ("g:a", "g:b")]
            assert len(consumers) == 2
            assert all(e["selectedVersion"] == expected for e in consumers)
        else:
            assert not report["selectedSources"], "Cancelled/Current choices changed a source"
        print(scenario + ": passed", flush=True)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)
        os.close(slave)


with tempfile.TemporaryDirectory(prefix="deplens-console-") as directory:
    root = Path(directory)
    plugin_path = str(PLUGIN).replace("\\", "\\\\").replace("'", "\\'")
    (root / "settings.gradle").write_text(
        "pluginManagement { repositories { mavenCentral(); gradlePluginPortal() }; includeBuild('" + plugin_path + "') }\nrootProject.name='console-test'\ninclude 'sub'\nincludeBuild 'nested'\n"
    )
    for build in ('sub', 'buildSrc', 'nested'):
        project = root / build
        project.mkdir()
        if build != 'sub':
            (project / 'settings.gradle').write_text(f"rootProject.name='{build}'\n")
        (project / 'build.gradle').write_text("""plugins { id 'java' }
repositories { maven { url=uri('../repo') } }
dependencies { implementation 'g:context:1.0' }
""")
    (root / "gradle").mkdir()
    catalog = root / "gradle/libs.versions.toml"
    catalog.write_text('''[versions]
shared="1.0"
[libraries]
aa={module="g:a", version.ref="shared"}
bb={module="g:b", version.ref="shared"}
unused={module="g:unused", version.ref="shared"}
''')
    (root / "build.gradle").write_text('''plugins { id 'java'; id 'com.sneedit.deplens' }
repositories { maven { url=uri('repo') } }
configurations { hiddenTooling { canBeResolved=true; canBeConsumed=false } }
dependencies { implementation libs.aa; implementation libs.bb; hiddenTooling 'g:hidden:1.0' }
depLens {
outputDeclaredConfigurationFilter { excludes=['hiddenTooling'] }
rules {
    global { pinned=true }
    dependency('g:a') { pinned=false }
    dependency('g:b') { pinned=false }
    dependency('g:hidden') { pinned=false }
} }
''')
    for module in ("a", "b", "context", "hidden"):
        base = root / "repo/g" / module
        for version in ("1.0", "1.1", "2.0"):
            path = base / version
            path.mkdir(parents=True)
            (path / f"{module}-{version}.pom").write_text(
                f"<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>{module}</artifactId><version>{version}</version></project>"
            )
            with zipfile.ZipFile(path / f"{module}-{version}.jar", "w"):
                pass
        (base / "maven-metadata.xml").write_text(
            "<metadata><groupId>g</groupId><artifactId>" + module + "</artifactId><versioning><versions>"
            "<version>1.0</version><version>1.1</version><version>2.0</version></versions></versioning></metadata>"
        )
    check = subprocess.run([GRADLE, 'depLensCheck', '--offline',
                            '-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME', *EXTRA_GRADLE_ARGS],
                           cwd=root, capture_output=True, text=True)
    assert check.returncode == 0, check.stdout + check.stderr
    check_report = json.loads((root / 'build/reports/deplens/depLensCheck.json').read_text())
    assert check_report['hiddenEntries'] == 1, 'Excluded dependency was not retained in the analyzed scope'
    exercise(root, "cancel", b"\x1b", "1.0")
    first_report = (root / 'build/reports/deplens/depLensInteractiveUpdate.json').read_bytes()
    identities = lambda report: {(e['build'], e['project'], e['type'], e['coordinate']) for e in report['entries']}
    assert identities(json.loads(first_report)) == identities(check_report), 'Check and interactive report visibility differ'

    exercise(root, "cancel-reused", b"\x1b", "1.0")
    assert (root / 'build/reports/deplens/depLensInteractiveUpdate.json').read_bytes() == first_report, 'Cancelled cache-hit report differs'
    if CACHE_ENABLED:
        assert 'Configuration cache entry stored' in (root / 'cancel.log').read_text()
        cached = (root / 'cancel-reused.log').read_text()
        assert 'Reusing configuration cache' in cached and 'Configuration cache entry reused' in cached, cached[-4000:]
        assert 'Calculating task graph' not in cached
    exercise(root, "apply", b"\x1b[C\r", "1.1")
    if CACHE_ENABLED:
        assert 'Reusing configuration cache' in (root / 'apply.log').read_text(), 'Apply did not run on a cache hit'
    catalog.write_text(catalog.read_text().replace('shared="1.1"', 'shared="1.+"'))
    exercise(root, "dynamic-bulk-current", b"l\r", "1.+")
    if CACHE_ENABLED:
        assert 'Configuration cache entry stored' in (root / 'dynamic-bulk-current.log').read_text(), 'Catalog edit did not invalidate configuration'

    # Exact local distribution sidecars exercise real wrapper updates without network access.
    mirror = root / 'mirror'
    mirror.mkdir()
    hashes = {'bin': 'ab' * 32, 'all': 'cd' * 32}
    for kind, checksum in hashes.items():
        (mirror / f'gradle-9.0.0-{kind}.zip.sha256').write_text(checksum + '\n')
    release_feed = root / 'releases.json'
    release_feed.write_text('[{"version":"8.8"},{"version":"9.0.0"}]')
    with (root / 'build.gradle').open('a') as script:
        script.write("\ndepLens { gradle { versionsUrl = file('releases.json').toURI().toString() }; rules { gradle { pinned=false } } }\n")
    originals = {}
    for prefix, kind in (('', 'bin'), ('nested', 'all')):
        wrapper = root / prefix / 'gradle/wrapper/gradle-wrapper.properties'
        wrapper.parent.mkdir(parents=True, exist_ok=True)
        text = (f"# wrapper\r\ndistributionUrl={mirror.as_uri()}/gradle-8.8-{kind}.zip\r\n"
                f"distributionSha256Sum={'0' * 64}\r\nnetworkTimeout=10000\r\n")
        wrapper.write_bytes(text.encode())
        originals[wrapper] = (text, kind)
    exercise(root, 'wrapper-cancel', b'\x1b', '1.+')
    for wrapper, (text, _) in originals.items():
        assert wrapper.read_bytes() == text.encode(), 'Cancellation changed the wrapper'
    exercise(root, 'wrapper-apply', b'l\r', '1.+', wrapper_expected='9.0.0')
    if CACHE_ENABLED:
        assert 'Reusing configuration cache' in (root / 'wrapper-apply.log').read_text(), 'Wrapper apply did not reuse the configuration cache'
    for wrapper, (text, kind) in originals.items():
        assert wrapper.read_bytes() == text.replace('8.8', '9.0.0').replace('0' * 64, hashes[kind]).encode(), 'URL/checksum pair or CRLF changed incorrectly'
        wrapper.write_bytes(text.encode())
    # A failed checksum in an included build must prevent root-wrapper and all other edits too.
    (mirror / 'gradle-9.0.0-all.zip.sha256').write_text('invalid checksum')
    exercise(root, 'wrapper-invalid-checksum', b'l\r', '1.+', failure=True)
    for wrapper, (text, _) in originals.items():
        assert wrapper.read_bytes() == text.encode(), 'Checksum failure applied a partial selection'
