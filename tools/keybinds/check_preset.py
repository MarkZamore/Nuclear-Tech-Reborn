"""Verify the preset, its overlay pin, and optionally the audited mod bytes."""
import argparse
import hashlib
import json
from pathlib import Path

from build_preset import PRESET, ROOT, TOOLS, check_layout, load_bindings, render


def fingerprint(data):
    return {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def verify(mods=None):
    rows = load_bindings()
    check_layout(rows)
    expected = render(rows)
    if PRESET.read_bytes() != expected:
        raise ValueError("Rebuild launcher/controls-preset.txt from bindings.tsv")
    pin = {"path": "launcher/controls-preset.txt", **fingerprint(expected)}
    recipe = json.loads((ROOT / "tools/overlay.json").read_text(encoding="utf-8"))
    if [row for row in recipe["additions"] if row["path"] == pin["path"]] != [pin]:
        raise ValueError("Preset bytes do not match the checked overlay")
    reviewed = json.loads((TOOLS / "reviewed-jars.json").read_text(encoding="utf-8"))["jars"]
    if mods is not None:
        for name, sha256 in reviewed.items():
            with (mods / name).open("rb") as handle:
                if hashlib.file_digest(handle, "sha256").hexdigest() != sha256:
                    raise ValueError(f"Review key registrations after a change to {name}")
    print(f"{len(rows)} actions, {sum(len(row.ids) for row in rows)} options IDs, "
          f"zero overlapping shortcuts in the reviewed contexts, SHA-256 {pin['sha256']}")
    return pin


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods", type=Path, help="folder containing the audited mod JARs")
    verify(parser.parse_args().mods)
