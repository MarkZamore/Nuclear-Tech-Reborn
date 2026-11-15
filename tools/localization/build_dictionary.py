"""Build the pack's Russian dictionary from pinned author strings and hand translations."""
import argparse
from base64 import b64encode
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent.parent
BOOKS = "config/lanminecraft/nuclear-book-ru.tsv"
QUEST_ROOT = "config/betterquesting/DefaultQuests"
COLOR = re.compile(r"§[0-9a-fk-or]", re.IGNORECASE)
URL = re.compile(r"https?://[^\s§\"<>]+")
MOD_FILES = {
    "mods/BetterQuestingUnofficial-4.3.2.jar",
    "mods/theoneprobe-1.12-1.4.28.jar",
}


def fingerprint(data):
    return {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def text_id(original):
    return hashlib.sha256(original.encode("utf-8")).hexdigest()[:16]


def has_quest_path(path):
    return path == QUEST_ROOT + ".json" or path.startswith(QUEST_ROOT + "/") and path.endswith(".json")


def source_texts(archive, lock):
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock["client"]["sha256"]:
        raise ValueError("Use the author archive checked against the current upstream lock")
    sources = {}
    inputs = {}
    counts = Counter()
    with ZipFile(archive) as reader:
        for row in lock["overrides"]:
            path = row["path"]
            if not has_quest_path(path):
                continue
            payload = reader.read(row["archivePath"])
            pin = fingerprint(payload)
            if any(pin[key] != row[key] for key in pin):
                raise ValueError(f"Source differs from lock: {path}")
            inputs[path] = pin
            document = json.loads(payload)
            if path == QUEST_ROOT + ".json":
                rows = [*document["questDatabase:9"].values(), *document["questLines:9"].values()]
                counts.update(quests=len(document["questDatabase:9"]), lines=len(document["questLines:9"]))
            elif path.endswith("/QuestSettings.json"):
                continue
            else:
                rows = [document]
            for entry in rows:
                props = entry["properties:10"]["betterquesting:10"]
                for field in ("name:8", "desc:8"):
                    original = props[field]
                    if not original:
                        continue
                    key = text_id(original)
                    if sources.setdefault(key, original) != original:
                        raise ValueError(f"Duplicate source ID: {key}")
    for row in lock["files"]:
        if row["path"] in MOD_FILES:
            inputs[row["path"]] = {field: row[field] for field in ("sha256", "sizeBytes", "projectId", "fileId")}
    if set(inputs).intersection(MOD_FILES) != MOD_FILES:
        raise ValueError("Review the Better Questing and The One Probe archives for changed versions")
    return sources, inputs, counts


def read_translations(sources):
    translations = {}
    for part in sorted(TOOLS.glob("ru-*.jsonl")):
        for number, line in enumerate(part.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line:
                continue
            row = json.loads(line)
            key, value = row["id"], row["ru"]
            original = row["en"]
            if (key in translations or not isinstance(value, str) or not value or "\ufffd" in value
                    or text_id(original) != key or sources.get(key, original) != original):
                raise ValueError(f"Bad translation at {part.name}:{number} ({key})")
            translations[key] = (original, value)
    missing = sources.keys() - translations.keys()
    # Removed or revised author text remains in the translation memory for old saves.
    if missing:
        raise ValueError(f"{len(missing)} author strings need review: {', '.join(sorted(missing)[:16])}")
    return translations


def compare_format(key, before, after):
    checks = [
        ("colors", COLOR.findall(before), COLOR.findall(after)),
        ("URLs", Counter(URL.findall(before)), Counter(URL.findall(after))),
        ("line breaks", before.count("\n"), after.count("\n")),
        ("literal line breaks", before.count(r"\n"), after.count(r"\n")),
    ]
    return [f"{key}: {label}" for label, old, new in checks if old != new]


def encode_rows(quests, probe):
    def entry(group, original, translated):
        fields = [b64encode(text.encode("utf-8")).decode("ascii") for text in (original, translated)]
        return "\t".join((group, *fields))

    records = ["NTR-RU-1"]
    for group, strings in (("q", quests), ("p", probe)):
        records.extend(entry(group, source, value) for source, value in sorted(strings.items()) if source != value)
    return ("\n".join(records) + "\n").encode("ascii")


def build(archive, verify):
    lock = json.loads((ROOT / "tools/upstream-lock.json").read_text(encoding="utf-8"))
    sources, inputs, counts = source_texts(archive, lock)
    translations = read_translations(sources)
    errors = [error for key, (original, translated) in translations.items()
              for error in compare_format(key, original, translated)]
    if errors:
        raise ValueError("Translation markup differs:\n" + "\n".join(errors))
    probe = json.loads((TOOLS / "top-ru.json").read_text(encoding="utf-8"))
    if not probe or not all(isinstance(value, str) and value for value in probe.values()):
        raise ValueError("The One Probe dictionary must contain nonempty strings")
    quests = dict(translations.values())
    data = encode_rows(quests, probe)
    output = ROOT / BOOKS
    recipe_path = ROOT / "tools/overlay.json"
    recipe = json.loads(recipe_path.read_text(encoding="utf-8"))
    pin = {"path": BOOKS, "inputs": dict(sorted(inputs.items())), "output": fingerprint(data)}
    if verify:
        if output.read_bytes() != data or recipe["bookLocalization"] != [pin]:
            raise ValueError("Rebuild the Russian dictionary and its overlay pins")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        recipe["bookLocalization"] = [pin]
        recipe_path.write_text(json.dumps(recipe, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({**counts, "uniqueStrings": len(sources), "translated": sum(a != b for a, b in quests.items()),
                      "probeTexts": len(probe), "inputFiles": len(inputs), "dictionary": fingerprint(data)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("author_archive", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    build(args.author_archive, args.verify)
