from pathlib import Path
import json
import sys

root = Path(sys.argv[1])
label = sys.argv[2]
out_path = Path(sys.argv[3]) if len(sys.argv) > 3 else None
files = sorted(root.rglob('*benchmarkData.json'))
if not files:
    raise SystemExit(f'no benchmarkData.json under {root}')
lines = [f'# {label}']
for path in files:
    data = json.loads(path.read_text(encoding='utf-8'))
    context = data.get('context', {})
    build = context.get('build', {})
    lines.append(f"file: {path.relative_to(root)}")
    lines.append(f"device: {build.get('model')} sdk={build.get('version', {}).get('sdk')} cores={context.get('cpuCoreCount')} mem={context.get('memTotalBytes')}")
    for bench in data.get('benchmarks', []):
        lines.append(f"\n[{bench.get('name')}]")
        for name, metric in sorted(bench.get('metrics', {}).items()):
            lines.append(f"  {name}: median={metric.get('median')} min={metric.get('minimum')} max={metric.get('maximum')} cv={metric.get('coefficientOfVariation')} runs={metric.get('runs')}")
        for name, metric in sorted(bench.get('sampledMetrics', {}).items()):
            line = f"  {name}: P50={metric.get('P50')} P90={metric.get('P90')} P95={metric.get('P95')} P99={metric.get('P99')}"
            if 'runs' in metric:
                line += f" perIterationP50={[sorted(r)[len(r)//2] if r else None for r in metric['runs']]}"
            lines.append(line)
text = '\n'.join(lines) + '\n'
print(text)
if out_path:
    out_path.write_text(text, encoding='utf-8')
