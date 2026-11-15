"""Apply the pack-owned Java 8 skin bootstrap using the shared CurseForge tools."""
import json
from pathlib import Path
import re
import sys
from zipfile import BadZipFile


AGENT = "lanminecraft/nuclear-legacy-skins.jar"
PRESET = "launcher/controls-preset.txt"
AUTHLIB = {
    "version": "1.5.25",
    "sha256": "c8a095123be93c59d4ac29e4a0616495b3d479ac65913cb719a81ee583e9dfcd",
    "sessionClassSha256": "aa73c7c67dd321b50cf4f0c148896af500e53668cac28d74d963af1188854ba7",
}


def install(prepare):
    original = prepare.overlay_bootstrap
    # The shared tool uses this set for pinned additions in both the overlay and its manifest verifier.
    prepare.LOGGING_FILES = prepare.LOGGING_FILES | {PRESET}

    def verify_skin_bootstrap(recipe, pack_source, author, additions):
        bootstrap = recipe.get("bootstrap")
        if not isinstance(bootstrap, dict) or "authlib" not in bootstrap:
            return original(recipe, pack_source, author, additions)
        require = prepare.require
        require(set(bootstrap) == {"authlib", "artifact"} and bootstrap["authlib"] == AUTHLIB,
                "Review Nuclear Tech Reborn's exact Java 8 Authlib compatibility")
        platform = author["pack"]
        descriptor_bytes = prepare.overlay_bytes(pack_source / "portable-pack.json")
        descriptor = json.loads(descriptor_bytes)
        require(platform["minecraftVersion"] == "1.12.2" and platform["loader"] ==
                {"type": "forge", "version": "14.23.5.2859"} and descriptor["javaMajorVersion"] == 8,
                "Review the skin agent for a changed game or Java platform")
        artifact = bootstrap["artifact"]
        require(isinstance(artifact, dict) and set(artifact) == {"path", "sizeBytes", "sha256"}
                and artifact["path"] == AGENT and prepare.positive(artifact["sizeBytes"])
                and artifact["sizeBytes"] <= prepare.MAX_BOOTSTRAP_BYTES
                and re.fullmatch(r"[a-f0-9]{64}", artifact["sha256"]),
                "Invalid Java 8 skin agent pin")
        require(not any(row["path"].casefold() == AGENT.casefold()
                        for row in [*author["overrides"], *author.get("files", [])]),
                "Java 8 skin agent path collides with author payload")
        args = additions["launcher/jvm-args.txt"].decode("utf-8").splitlines()
        require(args == ["-Dlog4j.configurationFile=config/log4j2.xml", f"-javaagent:{AGENT}"],
                "Java 8 skin agent must retain the original JVM logging option")
        data = prepare.overlay_bytes(pack_source / AGENT)
        require(prepare.fingerprint(data) == {key: artifact[key] for key in ("sizeBytes", "sha256")},
                "Java 8 skin agent differs from its pin")
        try:
            prepare.validate_bootstrap_jar(data)
        except BadZipFile as error:
            raise ValueError("Invalid Java 8 skin agent JAR") from error
        return {AGENT: data}

    prepare.overlay_bootstrap = verify_skin_bootstrap


def main():
    launcher, mode, *arguments = sys.argv[1:]
    tools = Path(launcher).resolve() / "tools/PackData/CurseForge"
    sys.path.insert(0, str(tools))
    import prepare
    import release_overlay
    import stage_release

    if mode not in {"stage", "verify", "review-update", "release"}:
        raise SystemExit("Expected stage, verify, review-update or release")
    install(prepare)
    sys.argv = [sys.argv[0], *([] if mode == "release" else [mode]), *arguments]
    if mode == "release":
        stage_release.main()
    else:
        release_overlay.main()


if __name__ == "__main__":
    main()
