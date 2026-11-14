"""Carry the public Nuclear Tech Reborn payload forward, adding its Java 8 skins and launcher settings."""
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
from urllib.request import Request, urlopen
import zipfile

BASE = "https://github.com/MarkZamore/Nuclear-Tech-Reborn/releases/download/pack-latest/"
REVISION = "50d49075edf7aa1b4eb2a1b1f16af2837e451fc1"
MANIFEST_SHA = "8ce0e4bf5ae3fc7c49189617e1bf46596b4f6a0005b8156dcec137d765ed1913"
JVM = "launcher/jvm-args.txt"
OWNERSHIP = "launcher/pack-owned.txt"
AGENT = "lanminecraft/nuclear-legacy-skins.jar"


def pin(data):
    return {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def fetch(row):
    with urlopen(BASE + row["name"], timeout=30) as response:
        data = response.read(row["sizeBytes"] + 1)
    if pin(data) != {key: row[key] for key in ("sizeBytes", "sha256")}:
        raise ValueError(f"Public asset differs from its pin: {row['name']}")
    return data


def bundle(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for path, data in sorted(entries.items()):
            info = zipfile.ZipInfo(path, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(info, data)
    return buffer.getvalue()


def git(*args):
    source = Path(__file__).resolve().parents[2]
    return subprocess.check_output(["git", "-C", str(source), *args])


def stage(output, revision):
    commit = git("rev-parse", "--verify", revision + "^{commit}").decode().strip()
    timestamp = int(git("show", "-s", "--format=%ct", commit))
    request = Request(BASE + "pack-manifest.json?candidate=" + commit, headers={"Cache-Control": "no-cache"})
    with urlopen(request, timeout=30) as response:
        old_manifest = response.read(2 * 1024 * 1024)
    manifest = json.loads(old_manifest)
    if pin(old_manifest)["sha256"] != MANIFEST_SHA or (manifest["packId"], manifest["minecraftVersion"],
            manifest["schemaVersion"], manifest["revision"]) != ("minecraft-24", "1.12.2", 2, REVISION):
        raise ValueError("The pinned public Nuclear Tech Reborn manifest changed")
    files = {row["path"]: row for row in manifest["files"]}
    launcher = next(row for row in manifest["assets"] if JVM in row["covers"])
    if AGENT in files or any(row["path"].startswith("lanminecraft/") for row in files.values()):
        raise ValueError("Base must not ship a Java bootstrap already")
    if launcher["kind"] != "zipRoot" or sorted(launcher["covers"]) != sorted([JVM, OWNERSHIP]):
        raise ValueError("Unexpected original launcher asset")
    added = git("show", f"{commit}:{AGENT}")
    options = git("show", f"{commit}:{JVM}")
    ownership = git("show", f"{commit}:{OWNERSHIP}")
    with zipfile.ZipFile(io.BytesIO(fetch(launcher))) as archive:
        if sorted(archive.namelist()) != sorted(launcher["covers"]):
            raise ValueError("The original launcher asset has unexpected contents")
        for path in (JVM, OWNERSHIP):
            if files[path] != {"path": path, **pin(archive.read(path))}:
                raise ValueError("Original launcher asset differs from its file pin")
        if options != archive.read(JVM) + f"-javaagent:{AGENT}\n".encode("ascii"):
            raise ValueError("Only the skin agent JVM option may be added")
        if ownership != archive.read(OWNERSHIP) + (AGENT + "\n").encode("ascii"):
            raise ValueError("Only the skin agent ownership may be added")
    recipe = json.loads(git("show", f"{commit}:tools/overlay.json"))
    for path, data in {AGENT: added, JVM: options, OWNERSHIP: ownership}.items():
        files[path] = {"path": path, **pin(data)}
    if (files[AGENT] != recipe["bootstrap"]["artifact"]
            or files[JVM] not in recipe["additions"]):
        raise ValueError("Candidate doesn't match the pack's checked recipe")

    def make_asset(entries, group):
        data = bundle(entries)
        asset_pin = pin(data)
        row = {"name": f"r-{asset_pin['sha256'][:12]}-{group}.zip",
               "covers": sorted(entries), "kind": "zipRoot", **asset_pin}
        return row, data

    new_agent, agent_zip = make_asset({AGENT: added}, "lanminecraft")
    new_launcher, launcher_zip = make_asset({JVM: options, OWNERSHIP: ownership}, "launcher")
    with zipfile.ZipFile(io.BytesIO(fetch(manifest["library"]))) as archive:
        if sorted(archive.namelist()) != ["library.json", "preview.jpg"]:
            raise ValueError("Unexpected library bundle")
        library = json.loads(archive.read("library.json"))
        if library["revision"] != REVISION:
            raise ValueError("Unexpected public library revision")
        library["revision"] = commit
        library_zip = bundle({"library.json": (json.dumps(library, ensure_ascii=False, indent=2) + "\n").encode(),
                              "preview.jpg": archive.read("preview.jpg")})
    library_pin = pin(library_zip)
    new_library = {"name": f"library-{library_pin['sha256']}.zip", "revision": commit, **library_pin}
    manifest.update(revision=commit, library=new_library,
                    generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                    files=[files[name] for name in sorted(files)])
    manifest["assets"].remove(launcher)
    manifest["assets"].extend([new_agent, new_launcher])
    manifest["assets"].sort(key=lambda row: row["name"])
    if sorted(files) != sorted(path for row in manifest["assets"] for path in row["covers"]):
        raise ValueError("The manifest must cover every file once")
    output.mkdir()
    (output / new_agent["name"]).write_bytes(agent_zip)
    (output / new_launcher["name"]).write_bytes(launcher_zip)
    (output / new_library["name"]).write_bytes(library_zip)
    (output / "previous-manifest.json").write_bytes(old_manifest)
    final = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    (output / "pack-manifest.json").write_bytes(final)
    print(json.dumps({"revision": commit, "manifest": pin(final), "agent": new_agent,
                      "launcher": new_launcher, "library": new_library, "preservedFiles": len(files)-3,
                      "preservedAssets": len(manifest["assets"])-2}, indent=2))


if __name__ == "__main__":
    stage(Path(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else "HEAD")
