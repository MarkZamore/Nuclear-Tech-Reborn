"""Stage a preset-only update of the verified published Nuclear Tech Reborn pack."""
import argparse
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen
import zipfile

from check_preset import verify
from build_preset import ROOT

sys.path.insert(0, str(ROOT / "tools/skin-agent"))
from prepare_release import PRESET, install
from stage_update import BASE, bundle, fetch, git, pin


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def read_base(expected_sha, nonce):
    request = Request(BASE + "pack-manifest.json?controls=" + nonce, headers={"Cache-Control": "no-cache"})
    with urlopen(request, timeout=45) as response:
        content = response.read(2_000_001)
    if len(content) > 2_000_000 or pin(content)["sha256"] != expected_sha:
        raise ValueError("The published base manifest differs from the reviewed SHA-256")
    return json.loads(content)


def stage(launcher_source, output, revision, base_sha):
    preset_pin = verify()
    if not re.fullmatch(r"[a-f0-9]{64}", base_sha):
        raise ValueError("Expected the full SHA-256 of the base manifest")
    commit = git("rev-parse", "--verify", revision + "^{commit}").decode().strip()
    for path in (PRESET, "tools/overlay.json"):
        if git("show", f"{commit}:{path}") != (ROOT / path).read_bytes():
            raise ValueError(f"Source differs from the candidate commit: {path}")
    manifest = read_base(base_sha, commit)
    if (manifest["packId"], manifest["minecraftVersion"], manifest["loader"], manifest["schemaVersion"]) != (
        "minecraft-24", "1.12.2", {"type": "forge", "version": "14.23.5.2859"}, 2
    ):
        raise ValueError("The public manifest is for another platform")
    git("merge-base", "--is-ancestor", manifest["revision"], commit)

    files = {row["path"]: row for row in manifest["files"]}
    launcher_paths = {"launcher/jvm-args.txt", "launcher/pack-owned.txt"}
    if PRESET in files:
        launcher_paths.add(PRESET)
    old_launcher, = [row for row in manifest["assets"] if "launcher/jvm-args.txt" in row["covers"]]
    if old_launcher["kind"] != "zipRoot" or set(old_launcher["covers"]) != launcher_paths:
        raise ValueError("Unexpected launcher asset layout")
    with zipfile.ZipFile(io.BytesIO(fetch(old_launcher))) as archive:
        if sorted(archive.namelist()) != sorted(launcher_paths):
            raise ValueError("Unexpected paths in the published launcher archive")
        entries = {path: archive.read(path) for path in launcher_paths}
    for path, data in entries.items():
        if {"path": path, **pin(data)} != files[path]:
            raise ValueError(f"Published launcher file differs from its pin: {path}")
    entries[PRESET] = git("show", f"{commit}:{PRESET}")
    files[PRESET] = preset_pin
    launcher_zip = bundle(entries)
    launcher_pin = pin(launcher_zip)
    launcher_name = f"r-{launcher_pin['sha256'][:12]}-launcher.zip"

    with zipfile.ZipFile(io.BytesIO(fetch(manifest["library"]))) as archive:
        if sorted(archive.namelist()) != ["library.json", "preview.jpg"]:
            raise ValueError("Unexpected public library archive")
        library = json.loads(archive.read("library.json"))
        if library["revision"] != manifest["revision"]:
            raise ValueError("The library does not match the published pack revision")
        library["revision"] = commit
        library_zip = bundle({"library.json": json_bytes(library), "preview.jpg": archive.read("preview.jpg")})
    library_pin = pin(library_zip)
    library_name = f"library-{library_pin['sha256']}.zip"
    manifest["assets"].remove(old_launcher)
    manifest["assets"].append({"name": launcher_name, "kind": "zipRoot", "covers": sorted(entries), **launcher_pin})
    manifest["assets"].sort(key=lambda row: row["name"])
    if sorted(files) != sorted(path for row in manifest["assets"] for path in row["covers"]):
        raise ValueError("The candidate payload must cover each file exactly once")
    timestamp = int(git("show", "-s", "--format=%ct", commit))
    manifest.update(revision=commit,
                    library={"name": library_name, "revision": commit, **library_pin},
                    files=[files[path] for path in sorted(files)],
                    generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))

    sys.path.insert(0, str(launcher_source / "tools/PackData/CurseForge"))
    import prepare
    import release_overlay

    install(prepare)
    author = release_overlay.read_lock(ROOT / "tools/upstream-lock.json", "complete")
    release_overlay.same_author(release_overlay.read_lock(ROOT / "tools/upstream-client-lock.json", "client-only"),
                               author)
    recipe, _, _, descriptor_pin = release_overlay.source_policy(ROOT, author)
    policy_files = {"portable-pack.json": descriptor_pin,
                    "launcher/pack-owned.txt": pin((ROOT / "launcher/pack-owned.txt").read_bytes())}
    policy_files.update({row["path"]: {key: row[key] for key in ("sizeBytes", "sha256")}
                         for row in [*recipe["additions"], recipe["bootstrap"]["artifact"]]})
    policy_files.update({row["path"]: row["after"] for row in recipe["patches"]})
    policy_files.update({row["path"]: row["output"] for row in recipe.get("bookLocalization", [])})
    release_overlay.verify_manifest(manifest, policy_files, author)

    output.mkdir()
    (output / launcher_name).write_bytes(launcher_zip)
    (output / library_name).write_bytes(library_zip)
    (output / "pack-manifest.json").write_bytes(json_bytes(manifest))
    print(f"Verified {len(files)} file pins against the full author lock and overlay. "
          f"Candidate revision {commit}, launcher asset {launcher_name}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("launcher_source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--base-sha256", required=True)
    args = parser.parse_args()
    stage(args.launcher_source.resolve(), args.output, args.revision, args.base_sha256)
