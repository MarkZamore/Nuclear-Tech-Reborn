"""Stage the checked launcher skins and Simple RPC update of Nuclear Tech Reborn."""
import argparse
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import re
import sys
import tomllib
from urllib.request import Request, urlopen
from zipfile import ZipFile

from prepare_release import AGENT, BOOKS, install
from stage_update import BASE, OWNERSHIP, bundle, fetch, git, pin


ROOT = Path(__file__).resolve().parents[2]
RPC = "simple-rpc/simple-rpc.toml"


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def read_base(expected_sha, commit):
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha):
        raise ValueError("Expected the full SHA-256 of the published base manifest")
    request = Request(BASE + "pack-manifest.json?skins=" + commit, headers={"Cache-Control": "no-cache"})
    with urlopen(request, timeout=45) as response:
        content = response.read(2_000_001)
    if len(content) > 2_000_000 or pin(content)["sha256"] != expected_sha:
        raise ValueError("The published base manifest differs from the reviewed SHA-256")
    return json.loads(content)


def validate_payload(launcher_source, manifest, commit):
    sys.path.insert(0, str(launcher_source / "tools/PackData/CurseForge"))
    import prepare
    import release_overlay

    install(prepare)
    lock_paths = ("tools/upstream-lock.json", "tools/upstream-client-lock.json")
    for path in (AGENT, BOOKS, OWNERSHIP, "portable-pack.json", "tools/overlay.json", *lock_paths):
        if git("show", f"{commit}:{path}") != (ROOT / path).read_bytes():
            raise ValueError(f"Working tree differs from the candidate commit: {path}")
    author = release_overlay.read_lock(ROOT / lock_paths[0], "complete")
    release_overlay.same_author(release_overlay.read_lock(ROOT / lock_paths[1], "client-only"), author)
    recipe, _, _, descriptor_pin = release_overlay.source_policy(ROOT, author)
    policy = {"portable-pack.json": descriptor_pin, OWNERSHIP: pin((ROOT / OWNERSHIP).read_bytes())}
    policy.update({row["path"]: {key: row[key] for key in ("sizeBytes", "sha256")}
                   for row in [*recipe["additions"], recipe["bootstrap"]["artifact"]]})
    policy.update({row["path"]: row["after"] for row in recipe["patches"]})
    policy.update({row["path"]: row["output"] for row in recipe["bookLocalization"]})
    release_overlay.verify_manifest(manifest, policy, author)


def disable_rpc(content, patch):
    actual = pin(content)
    if actual == patch["after"] and tomllib.loads(content.decode("utf-8"))["general"]["enabled"] is False:
        return content
    if actual != patch["before"]:
        raise ValueError("Simple RPC config differs from its reviewed input pin")
    replacement, = patch["replacements"]
    before, after = replacement["from"].encode("utf-8"), replacement["to"].encode("utf-8")
    if content.count(before) != 1:
        raise ValueError("Simple RPC config must have one matching enable flag")
    changed = content.replace(before, after)
    settings = tomllib.loads(content.decode("utf-8"))
    if settings["general"]["enabled"] is not True:
        raise ValueError("Expected Simple RPC to be enabled before applying the overlay")
    settings["general"]["enabled"] = False
    if pin(changed) != patch["after"] or tomllib.loads(changed.decode("utf-8")) != settings:
        raise ValueError("Expected only the Simple RPC general enable flag to change")
    return changed


def replace_payload(manifest, files, path, label, replace):
    original, = [row for row in manifest["assets"] if path in row["covers"]]
    if original["kind"] != "zipRoot":
        raise ValueError(f"Expected a ZIP archive for {path}")
    with ZipFile(io.BytesIO(fetch(original))) as archive:
        if sorted(archive.namelist()) != sorted(original["covers"]):
            raise ValueError(f"Unexpected paths in {original['name']}")
        entries = {name: archive.read(name) for name in original["covers"]}
    for name, content in entries.items():
        if files[name] != {"path": name, **pin(content)}:
            raise ValueError(f"Published archive member differs from its pin: {name}")

    updated = replace(entries[path])
    if updated == entries[path]:
        return None
    entries[path] = updated
    files[path] = {"path": path, **pin(updated)}
    archive = bundle(entries)
    archive_pin = pin(archive)
    name = f"r-{archive_pin['sha256'][:12]}-{label}.zip"
    manifest["assets"].remove(original)
    manifest["assets"].append({"name": name, "kind": "zipRoot", "covers": sorted(entries), **archive_pin})
    return name, archive


def stage(launcher_source, output, revision, base_sha):
    if output.exists():
        raise ValueError(f"Use a fresh output directory: {output}")
    commit = git("rev-parse", "--verify", revision + "^{commit}").decode().strip()
    manifest = read_base(base_sha, commit)
    if (manifest["packId"], manifest["minecraftVersion"], manifest["loader"], manifest["schemaVersion"]) != (
        "minecraft-24", "1.12.2", {"type": "forge", "version": "14.23.5.2859"}, 2
    ):
        raise ValueError("The published manifest is for another platform")
    git("merge-base", "--is-ancestor", manifest["revision"], commit)

    files = {row["path"]: row for row in manifest["files"]}
    recipe = json.loads(git("show", f"{commit}:tools/overlay.json"))
    rpc_patch, = [patch for patch in recipe["patches"] if patch["path"] == RPC]
    candidate = git("show", f"{commit}:{AGENT}")
    ownership = git("show", f"{commit}:{OWNERSHIP}")
    rpc_line = (RPC + "\n").encode("utf-8")

    def replace_ownership(content):
        expected = content if rpc_line.rstrip(b"\n") in content.splitlines() else content + rpc_line
        if ownership != expected:
            raise ValueError("Only the Simple RPC ownership entry may be added")
        return ownership

    agent_asset = replace_payload(manifest, files, AGENT, "lanminecraft", lambda old: candidate)
    if agent_asset is None:
        raise ValueError("The candidate skin agent matches the published file")
    rpc_asset = replace_payload(manifest, files, RPC, "simple-rpc", lambda old: disable_rpc(old, rpc_patch))
    ownership_asset = replace_payload(manifest, files, OWNERSHIP, "launcher", replace_ownership)
    payloads = dict(asset for asset in (agent_asset, rpc_asset, ownership_asset) if asset is not None)
    manifest["assets"].sort(key=lambda row: row["name"])
    if sorted(files) != sorted(path for row in manifest["assets"] for path in row["covers"]):
        raise ValueError("The candidate must cover each file exactly once")

    with ZipFile(io.BytesIO(fetch(manifest["library"]))) as archive:
        if sorted(archive.namelist()) != ["library.json", "preview.jpg"]:
            raise ValueError("Unexpected public library archive")
        library = json.loads(archive.read("library.json"))
        if library["revision"] != manifest["revision"]:
            raise ValueError("The library does not match the published pack revision")
        library["revision"] = commit
        library_zip = bundle({"library.json": json_bytes(library), "preview.jpg": archive.read("preview.jpg")})
    library_pin = pin(library_zip)
    library_name = f"library-{library_pin['sha256']}.zip"
    timestamp = int(git("show", "-s", "--format=%ct", commit))
    manifest.update(revision=commit, library={"name": library_name, "revision": commit, **library_pin},
                    files=[files[path] for path in sorted(files)],
                    generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    validate_payload(launcher_source, manifest, commit)

    output.mkdir()
    for name, archive in payloads.items():
        (output / name).write_bytes(archive)
    (output / library_name).write_bytes(library_zip)
    final = json_bytes(manifest)
    (output / "pack-manifest.json").write_bytes(final)
    print(json.dumps({"revision": commit, "manifest": pin(final), "payloadAssets": sorted(payloads),
                      "libraryAsset": library_name,
                      "preservedPayloadAssets": len(manifest["assets"]) - len(payloads)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("launcher_source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--base-sha256", required=True)
    args = parser.parse_args()
    stage(args.launcher_source.resolve(), args.output, args.revision, args.base_sha256)
