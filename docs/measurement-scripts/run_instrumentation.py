from pathlib import Path
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
serial, package, test_class, output = sys.argv[1:]
adb = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'
result = subprocess.run(
    [str(adb), '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class,
     package + '/androidx.test.runner.AndroidJUnitRunner'],
    capture_output=True, timeout=600,
)
text = result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace')
Path(output).write_text(text, encoding='utf-8')
passed = result.returncode == 0 and re.search(r'OK \(\d+ tests?\)', text) is not None
print(text[-6000:])
print('VERIFIED RESULT:', 'PASS' if passed else 'FAIL')
sys.exit(0 if passed else 1)
