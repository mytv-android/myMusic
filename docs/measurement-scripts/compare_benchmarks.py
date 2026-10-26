from pathlib import Path
import json
import sys

before_dir = Path(sys.argv[1])
after_dir = Path(sys.argv[2])
out_path = Path(sys.argv[3]) if len(sys.argv) > 3 else None

BENCH_ORDER = ['coldStartup', 'chartsScroll', 'openPlayer']


def load(root):
    files = sorted(root.rglob('*benchmarkData.json'))
    if not files:
        raise SystemExit(f'no results under {root}')
    data = json.loads(files[-1].read_text(encoding='utf-8'))
    return {bench['name']: bench for bench in data.get('benchmarks', [])}, files[-1]


def get(block, metric, key):
    return block.get('metrics', {}).get(metric, {}).get(key)


def sample(block, metric, key):
    return block.get('sampledMetrics', {}).get(metric, {}).get(key)


def pct(before, after):
    if before in (None, 0) or after is None:
        return ''
    return f'{(after - before) / before * 100:+.1f}%'


def split_name(name):
    if name.endswith(']') and '[' in name:
        i = name.index('[')
        return name[:i], name[i + 1:-1]
    return name, 'default'


before, bfile = load(before_dir)
after, afile = load(after_dir)
lines = [f'before: {bfile}', f'after:  {afile}', '']
lines.append('| 场景 | 编译模式 | 指标 | 修复前 | 修复后 | 变化 |')
lines.append('|---|---|---|---:|---:|---:|')

before_modes = {}
after_modes = {}
for name in before:
    bench, mode = split_name(name)
    before_modes.setdefault(bench, set()).add(mode)
for name in after:
    bench, mode = split_name(name)
    after_modes.setdefault(bench, set()).add(mode)

for bench in BENCH_ORDER:
    for mode in sorted(before_modes.get(bench, set()) & after_modes.get(bench, set())):
        b = before.get(f'{bench}[{mode}]')
        a = after.get(f'{bench}[{mode}]')
        if not b or not a:
            continue
        rows = []
        if bench == 'coldStartup':
            mb = get(b, 'timeToInitialDisplayMs', 'median')
            ma = get(a, 'timeToInitialDisplayMs', 'median')
            rows.append(('启动到首帧中位数 (ms)', mb, ma))
        for key in ('P50', 'P90', 'P99'):
            rows.append((f'帧 CPU 时间 {key} (ms)', sample(b, 'frameDurationCpuMs', key), sample(a, 'frameDurationCpuMs', key)))
        for key in ('P50', 'P90', 'P99'):
            mb = sample(b, 'frameOverrunMs', key)
            ma = sample(a, 'frameOverrunMs', key)
            if mb is not None or ma is not None:
                rows.append((f'帧超时 {key} (ms)', mb, ma))
        for key in ('frameCount',):
            rows.append((f'{key} 中位数', get(b, key, 'median'), get(a, key, 'median')))
        for label, mb, ma in rows:
            fmt_b = f'{mb:.2f}' if isinstance(mb, (int, float)) else str(mb)
            fmt_a = f'{ma:.2f}' if isinstance(ma, (int, float)) else str(ma)
            lines.append(f'| {bench} | {mode} | {label} | {fmt_b} | {fmt_a} | {pct(mb, ma)} |')

text = '\n'.join(lines) + '\n'
print(text)
if out_path:
    out_path.write_text(text, encoding='utf-8')
