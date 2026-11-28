#!/usr/bin/env python3
"""Integrity checks for the device list (devices/oneplus.json and the copy shipped in the app).

Run by CI before the build. It does not decide which regions a phone is sold in (that needs a source);
it rejects lists that contradict themselves, which is how a region-exclusive phone ends up under the wrong region.
"""
import json, re, sys, collections
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIST = ROOT / "devices" / "oneplus.json"
BUNDLED = ROOT / "app" / "src" / "main" / "assets" / "oneplus_devices.json"
REGIONS = {"IN", "GLO", "EU", "CN", "Genshin Impact"}
CN_MODEL = re.compile(r"^(P[A-Z]{2,3}\d{2,3}[A-Z]{0,2}|NE2210|OPD24(04|13))$")


def main() -> int:
    errors = []
    if LIST.read_bytes() != BUNDLED.read_bytes():
        errors.append("devices/oneplus.json and app/src/main/assets/oneplus_devices.json differ")
    data = json.loads(LIST.read_text())
    claims = collections.defaultdict(set)
    for i, dev in enumerate(data):
        name = dev.get("name", f"#{i}")
        seen = set()
        for v in dev.get("variants", []):
            region, model, pname = v.get("displayName"), v.get("productModel"), v.get("productName")
            where = f"{name} [{region}]"
            if region not in REGIONS or not model:
                errors.append(f"{where}: missing/unknown displayName or productModel")
                continue
            key = (region, model, pname)
            if key in seen:
                errors.append(f"{where}: duplicate variant {model}/{pname}")
            seen.add(key)
            claims[(model, pname)].add(name)
            is_cn = region in ("CN", "Genshin Impact")
            if is_cn and model.startswith("CPH"):
                errors.append(f"{where}: China variant uses non-China model {model}")
            if not is_cn and CN_MODEL.match(model) and not model.startswith("OPD2"):
                errors.append(f"{where}: {model} looks like a China model but region is {region}")
            if region == "EU" and not (pname or "").endswith("EEA"):
                errors.append(f"{where}: EU productName should end with EEA, got {pname}")
            if region == "IN" and pname and not pname.endswith("IN"):
                errors.append(f"{where}: IN productName should end with IN, got {pname}")
            if region in ("GLO", "CN") and pname and re.search(r"(EEA|IN)$", pname) and not pname.startswith(model.rstrip("YS")):
                errors.append(f"{where}: productName {pname} carries another region's suffix")
    for (model, pname), owners in claims.items():
        if len(owners) > 1:
            errors.append(f"{model}/{pname} is claimed by more than one device: {sorted(owners)}")
    for e in errors:
        print("ERROR:", e)
    print(f"{len(data)} devices checked, {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
