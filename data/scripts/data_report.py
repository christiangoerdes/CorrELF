import argparse
from collections import Counter, defaultdict
from pathlib import Path
import sys

def main():
    parser = argparse.ArgumentParser(
        description="Count file families by prefix before '___'"
    )
    parser.add_argument(
        'directory',
        type=Path,
        help='Directory to scan for file families'
    )
    args = parser.parse_args()

    src = args.directory
    if not src.is_dir():
        print(f"Error: {src} is not a directory.")
        sys.exit(1)

    counts = Counter()
    members = defaultdict(list)
    for path in src.iterdir():
        if not path.is_file() or path.is_symlink():
            continue
        name = path.name
        if '___' in name:
            prefix = name.split('___', 1)[0]
        else:
            prefix = name
        counts[prefix] += 1
        members[prefix].append(name)

    script_dir = Path(__file__).resolve().parent
    report_path = script_dir / 'family_report_test.txt'
    with report_path.open('w') as report:
        report.write(f"File Family Report for: {src}\n")
        report.write("=======================================\n\n")
        for family, count in counts.most_common():
            report.write(f"Family '{family}': {count} files\n")
        report.write("\nDetailed members per family:\n")
        report.write("---------------------------------------\n")
        for family, files in counts.items():
            report.write(f"{family}: {', '.join(members[family])}\n")

    print(f"Report written to {report_path}")

if __name__ == '__main__':
    main()
