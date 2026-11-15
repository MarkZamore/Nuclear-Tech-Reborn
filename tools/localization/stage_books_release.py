"""Stage the book-localization update from a verified public Nuclear Tech Reborn release."""
import argparse
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/skin-agent"))
from prepare_release import AGENT, BOOKS, install
from stage_update import bundle, fetch, git, pin
sys.path.insert(0, str(ROOT / "tools/keybinds"))
from stage_preset_release import json_bytes, read_base


def stage(launcher_source, output, revision, base_sha):
    sys.path.insert(0, str(launcher_source / "tools/PackData/CurseForge"))
    import prepare
    import release_overlay

    install(prepare)
    commit = git("rev-parse", "--verify", revision + "^{commit}").decode().strip()
    manifest = read_base(base_sha, commit)
    if (manifest["packId"], manifest["minecraftVersion"], manifest["loader"], manifest["schemaVersion"]) != (
        "minecraft-24", "1.12.2", {"type": "forge", "version": "14.23.5.2859"}, 2
    ):
        raise ValueError("Unexpected base release")
    git("merge-base", "--is-ancestor", manifest["revision"], commit)
    if output.exists():
        raise ValueError(f"Use a fresh output directory: {output}")
    ownership = "launcher/pack-owned.txt"
    changed_paths = {AGENT, BOOKS, ownership}
    source_paths = changed_paths | {"tools/overlay.json", "tools/upstream-lock.json", "tools/upstream-client-lock.json"}
    for path in source_paths:
        if git("show", f"{commit}:{path}") != (ROOT / path).read_bytes():
            raise ValueError(f"Working tree differs from the candidate: {path}")

    assets = {}
    files = {row["path"]: row for row in manifest["files"]}
    for row in list(manifest["assets"]):
        if not changed_paths.intersection(row["covers"]):
            continue
        if row["kind"] != "zipRoot":
            raise ValueError(f"Expected an archive for {row['name']}")
        with ZipFile(io.BytesIO(fetch(row))) as previous:
            if sorted(previous.namelist()) != sorted(row["covers"]):
                raise ValueError(f"Unexpected members in {row['name']}")
            entries = {path: previous.read(path) for path in row["covers"]}
        for path, content in entries.items():
            if files[path] != {"path": path, **pin(content)}:
                raise ValueError(f"Public archive differs from pin for {path}")
            if path in changed_paths:
                entries[path] = (ROOT / path).read_bytes()
                files[path] = {"path": path, **pin(entries[path])}
        manifest["assets"].remove(row)
        label = "launcher" if ownership in entries else "lanminecraft" if AGENT in entries else "nuclear-books"
        data = bundle(entries)
        name = f"r-{pin(data)['sha256'][:12]}-{label}.zip"
        manifest["assets"].append({"name": name, "kind": "zipRoot", "covers": sorted(entries), **pin(data)})
        assets[name] = data

    if BOOKS not in files:
        dictionary = (ROOT / BOOKS).read_bytes()
        files[BOOKS] = {"path": BOOKS, **pin(dictionary)}
        data = bundle({BOOKS: dictionary})
        name = f"r-{pin(data)['sha256'][:12]}-nuclear-books.zip"
        manifest["assets"].append({"name": name, "kind": "zipRoot", "covers": [BOOKS], **pin(data)})
        assets[name] = data

    with ZipFile(io.BytesIO(fetch(manifest["library"]))) as previous:
        if sorted(previous.namelist()) != ["library.json", "preview.jpg"]:
            raise ValueError("Unexpected library members")
        library = json.loads(previous.read("library.json"))
        if library["revision"] != manifest["revision"]:
            raise ValueError("Library and published revision differ")
        library["revision"] = commit
        data = bundle({"library.json": json_bytes(library), "preview.jpg": previous.read("preview.jpg")})
    name = f"library-{pin(data)['sha256']}.zip"
    assets[name] = data
    manifest["assets"].sort(key=lambda row: row["name"])
    if sorted(files) != sorted(path for row in manifest["assets"] for path in row["covers"]):
        raise ValueError("The release must cover each file exactly once")
    timestamp = int(git("show", "-s", "--format=%ct", commit))
    manifest.update(revision=commit, library={"name": name, "revision": commit, **pin(data)},
                    files=[files[path] for path in sorted(files)],
                    generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))

    author = release_overlay.read_lock(ROOT / "tools/upstream-lock.json", "complete")
    release_overlay.same_author(release_overlay.read_lock(ROOT / "tools/upstream-client-lock.json", "client-only"),
                               author)
    recipe, _, _, descriptor_pin = release_overlay.source_policy(ROOT, author)
    policy = {"portable-pack.json": descriptor_pin, ownership: pin((ROOT / ownership).read_bytes())}
    patches = {row["path"] for row in recipe["patches"]}
    owned = (ROOT / ownership).read_text(encoding="utf-8").splitlines()
    if owned != sorted(patches | {prepare.LOGGING_CONFIG, AGENT, BOOKS}):
        raise ValueError("Ownership differs from the changed pack files")
    policy.update({row["path"]: {key: row[key] for key in ("sizeBytes", "sha256")}
                   for row in [*recipe["additions"], recipe["bootstrap"]["artifact"]]})
    policy.update({row["path"]: row["after"] for row in recipe["patches"]})
    policy.update({row["path"]: row["output"] for row in recipe["bookLocalization"]})
    release_overlay.verify_manifest(manifest, policy, author)
    output.mkdir()
    for asset, payload in assets.items():
        (output / asset).write_bytes(payload)
    (output / "pack-manifest.json").write_bytes(json_bytes(manifest))
    print(json.dumps({"revision": commit, "files": len(files), "changed": sorted(changed_paths),
                      "assets": sorted(assets), "manifest": pin(json_bytes(manifest))}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("launcher_source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--base-sha256", required=True)
    args = parser.parse_args()
    stage(args.launcher_source.resolve(), args.output, args.revision, args.base_sha256)
