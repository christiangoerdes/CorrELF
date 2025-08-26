from pathlib import Path
import json
import math
from typing import Dict, Any, List

DATA_DIR_CANDIDATES = [Path("../train_busybox_512_minhash/busybox")]
EXPORT_DIR = Path("./exports_512_minhash")
REMOVE_SELF_MATCHES = True
LOW_T = 0.05
HIGH_T = 0.7

def find_data_dir(cands: List[Path]) -> Path:
    for p in cands:
        if p.is_dir():
            return p
    raise SystemExit("No input folder found. Expected ./train/busybox (or similar).")

def load_json_array(p: Path) -> List[Dict[str, Any]]:
    with p.open("r", encoding="utf-8") as f:
        data = json.load(f)
        if not isinstance(data, list):
            raise ValueError(f"{p} is not a JSON array")
        return data

def is_busybox(name: str) -> bool:
    return isinstance(name, str) and "busybox" in name.lower()

def bb_id(name: str) -> str:
    if not isinstance(name, str):
        return ""
    s = name.lower()
    return s.split("busybox___", 1)[1] if "busybox___" in s else s

data_dir = find_data_dir(DATA_DIR_CANDIDATES)
json_files = sorted(data_dir.glob("*.json"))
if not json_files:
    raise SystemExit(f"No JSONs in {data_dir}")

# stats[rep]["busy"|"non"] = {"gt": x, "lt": y, "tot": z}
stats: Dict[str, Dict[str, Dict[str, float]]] = {}

for jf in json_files:
    arr = load_json_array(jf)
    src_name = (arr[0].get("secondFileName") if arr and isinstance(arr[0], dict) else jf.stem) or jf.stem
    src_is_bb = is_busybox(src_name)
    src_id = bb_id(src_name)

    for rec in arr:
        if not isinstance(rec, dict):
            continue
        tgt = rec.get("fileName", "")
        if REMOVE_SELF_MATCHES and src_is_bb and is_busybox(tgt) and bb_id(tgt) == src_id:
            continue

        comp = rec.get("comparisonDetails") or {}
        cls = "busy" if is_busybox(tgt) else "non"

        for rep, val in comp.items():
            try:
                v = float(val)
            except Exception:
                continue
            if not math.isfinite(v):
                continue

            srep = stats.setdefault(rep, {}).setdefault(cls, {"gt": 0, "lt": 0, "tot": 0})
            srep["tot"] += 1
            if v > HIGH_T:
                srep["gt"] += 1
            if v < LOW_T:
                srep["lt"] += 1

for rep, classes in stats.items():
    for cls, d in classes.items():
        tot = d.get("tot", 0) or 1
        d["gt_pct"] = round(d["gt"] / tot, 6)
        d["lt_pct"] = round(d["lt"] / tot, 6)



def fmt(rep: str, cls: str, d: Dict[str, float]) -> str:
    return (f"{rep:<22} {cls:<5} | "
            f">.95: {int(d['gt']):>6} ({d['gt_pct']*100:6.2f}%)   "
            f"<.05: {int(d['lt']):>6} ({d['lt_pct']*100:6.2f}%)   "
            f"tot: {int(d['tot']):>7}")

print("\nRepresentation threshold summary (BusyBox target vs Non-BusyBox target):")
for rep in sorted(stats.keys()):
    busy = stats[rep].get("busy", {"gt":0,"lt":0,"tot":0,"gt_pct":0.0,"lt_pct":0.0})
    non  = stats[rep].get("non",  {"gt":0,"lt":0,"tot":0,"gt_pct":0.0,"lt_pct":0.0})
    print(fmt(rep, "busy", busy))
    print(fmt(rep, "non",  non))
