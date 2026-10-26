from pathlib import Path
import os
import subprocess
import sys

serial, app_apk, test_apk, out_dir, label = sys.argv[1:6]
selector = sys.argv[6] if len(sys.argv) > 6 else None
adb = str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe')
out = Path(out_dir)
out.mkdir(parents=True, exist_ok=True)
test_pkg = 'com.mytv.mymusic.baselineprofile'
runner = f'{test_pkg}/androidx.test.runner.AndroidJUnitRunner'
if selector and '.' in selector:
    test_class = selector
else:
    test_class = 'com.mytv.mymusic.baselineprofile.FrameBenchmarks' + (f'#{selector}' if selector else '')


def run(*args, timeout=600, check=True):
    result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=timeout)
    if check and result.returncode != 0:
        raise SystemExit(f'adb failed: {args}\n{result.stdout.decode(errors="replace")}\n{result.stderr.decode(errors="replace")}')
    return result


run('shell', 'am', 'force-stop', test_pkg, check=False)
run('shell', 'am', 'force-stop', 'com.mytv.mymusic', check=False)
run('shell', 'rm', '-rf', f'/sdcard/Android/data/{test_pkg}/files/additional_test_output', check=False)
run('install', '-r', app_apk, timeout=300)
run('install', '-r', test_apk, timeout=300)
args = [
    'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'androidx.benchmark.enabledRules', 'Macrobenchmark',
    '-e', 'androidx.benchmark.output.enable', 'true',
    '-e', 'androidx.benchmark.suppressErrors', 'EMULATOR,LOW-BATTERY',
    '-e', 'additionalTestOutputDir', f'/sdcard/Android/data/{test_pkg}/files/additional_test_output',
    '-e', 'class', test_class,
    runner,
]
print('running:', ' '.join(args), flush=True)
result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=1800)
log = result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace')
(out / f'{label}-instrument.txt').write_text(log, encoding='utf-8')
print(log[-8000:], flush=True)

found = run('shell', 'find', f'/sdcard/Android/data/{test_pkg}', '-type', 'f', check=False)
paths = [line.strip() for line in found.stdout.decode('utf-8', errors='replace').splitlines()
         if 'benchmarkData' in line or 'additional_test_output' in line]
print('device output files:', paths, flush=True)
result_dir = out / f'{label}-results'
result_dir.mkdir(exist_ok=True)
for remote in paths:
    local = result_dir / remote.rstrip('/').split('/')[-1]
    subprocess.run([adb, '-s', serial, 'pull', remote, str(local)], capture_output=True, timeout=120)
if not any(result_dir.iterdir()):
    print(f'NO RESULT FILES for {label}', flush=True)
    sys.exit(1)
print(f'RESULT FILES READY for {label}:', [p.name for p in result_dir.iterdir()], flush=True)
if 'OK (' not in log:
    print('INSTRUMENTATION DID NOT REPORT OK', flush=True)
    sys.exit(1)
