import argparse, json, glob
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score

ALL_FEATURES   = ["PROGRAM_HEADER_VECTOR", "CODE_REGION_LIST", "STRING_MINHASH",
                  "ELF_HEADER_VECTOR", "SECTION_SIZE_VECTOR"]

# Exclude ELF_HEADER_VECTOR and SECTION_SIZE_VECTOR to prevent very small weights
TRAIN_FEATURES = ["PROGRAM_HEADER_VECTOR", "CODE_REGION_LIST", "STRING_MINHASH"]

# Returns True if a filename belongs to the family
def is_family(name: str, family_prefix: str = "busybox") -> bool:
    if not isinstance(name, str):
        return False
    s = name.lower()
    return s.startswith(family_prefix.lower()) and "___" in s

# Loads all JSONs from input_dir into a DataFrame with features and identifiers
def load_frames(input_dir: Path) -> pd.DataFrame:
    rows = []
    for jf in sorted(glob.glob(str(input_dir / "*.json"))):
        try:
            data = json.load(open(jf, "r", encoding="utf-8"))
        except Exception:
            continue
        for d in data:
            comp = d.get("comparisonDetails", {}) or {}
            rows.append({
                "anchor": d.get("secondFileName", ""),
                "candidate": d.get("fileName", ""),
                "PROGRAM_HEADER_VECTOR": comp.get("PROGRAM_HEADER_VECTOR", np.nan),
                "CODE_REGION_LIST":      comp.get("CODE_REGION_LIST", np.nan),
                "STRING_MINHASH":        comp.get("STRING_MINHASH", np.nan),
                "ELF_HEADER_VECTOR":     comp.get("ELF_HEADER_VECTOR", np.nan),
                "SECTION_SIZE_VECTOR":   comp.get("SECTION_SIZE_VECTOR", np.nan),
            })
    df = pd.DataFrame(rows)
    for c in ALL_FEATURES:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df[ALL_FEATURES] = df[ALL_FEATURES].fillna(0.0).clip(0.0, 1.0)
    df["anchor"]    = df["anchor"].astype(str)
    df["candidate"] = df["candidate"].astype(str)
    return df

# Prepares X,y by removing self-matches and labeling candidates by family membership
def prepare(df: pd.DataFrame, family_prefix: str):
    df = df.loc[~(df["anchor"].str.lower() == df["candidate"].str.lower())].copy()
    df["y"] = df["candidate"].apply(lambda x: 1 if is_family(x, family_prefix) else 0).astype(int)
    X = df[TRAIN_FEATURES].values
    y = df["y"].values
    return df, X, y

# Grid-searches an F1-maximizing threshold on a score vector
def best_threshold_for_scores(scores, y, grid=1001):
    s = np.asarray(scores, float)
    s = np.clip(s, 0.0, 1.0)
    best_f1, best_thr = -1.0, 0.5
    for t in np.linspace(0, 1, grid):
        f1 = f1_score(y, (s >= t).astype(int), zero_division=0)
        if f1 > best_f1:
            best_f1, best_thr = f1, t
    return float(best_thr), float(best_f1)

# Fits logistic regression on all data, converts coefficients to non-negative normalized weights,
# computes weighted linear score and the F1-optimal threshold for that score
def fit_weights_and_threshold(X, y, random_state=0):
    clf = LogisticRegression(max_iter=500,
                             class_weight="balanced", random_state=random_state).fit(X, y)
    coef = np.clip(clf.coef_[0], 0, None)
    weights = coef / coef.sum() if coef.sum() > 0 else np.ones_like(coef) / len(coef)
    wscore = (X * weights).sum(axis=1)  # linear weighted score in [0,1]
    thr_w, best_f1_w = best_threshold_for_scores(wscore, y)
    return weights.astype(float), thr_w, best_f1_w

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input-dir", required=True)          
    ap.add_argument("--family", default="busybox")         
    ap.add_argument("--output", default="model.json")   
    args = ap.parse_args()

    df_raw = load_frames(Path(args.input_dir))
    df, X, y = prepare(df_raw, args.family)
    weights, threshold, best_f1 = fit_weights_and_threshold(X, y, random_state=42)

    model = {
        "family": args.family,
        "features": TRAIN_FEATURES,
        "weights_sum_to_1": list(map(float, weights)),
        "threshold_weighted_score": float(threshold)
    }

    # Write JSON to disk
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)

if __name__ == "__main__":
    main()
