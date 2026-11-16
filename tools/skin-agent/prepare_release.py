"""Apply the pack-owned Java 8 skin bootstrap using the shared CurseForge tools."""
import json
from pathlib import Path
import re
import sys
from zipfile import BadZipFile


AGENT = "lanminecraft/nuclear-legacy-skins.jar"
PRESET = "launcher/controls-preset.txt"
BOOKS = "config/lanminecraft/nuclear-book-ru.tsv"
JOURNEYMAP = {
    "projectId": 32274,
    "fileId": 5172461,
    "path": "mods/journeymap-1.12.2-5.7.1p3.jar",
    "sizeBytes": 6851584,
    "sha256": "b72c9dd7e6dd0f7438c01ea8e440519bf4a3c0b47674810929a9c899dc98b406",
}
AUTHLIB = {
    "version": "1.5.25",
    "sha256": "c8a095123be93c59d4ac29e4a0616495b3d479ac65913cb719a81ee583e9dfcd",
    "sessionClassSha256": "aa73c7c67dd321b50cf4f0c148896af500e53668cac28d74d963af1188854ba7",
    "forgeSessionClassSha256": "e2519fbdb0a274214cb0755e05250f82b84d1cd4882a921e690cc52e5e2bf1ff",
}


def install(prepare):
    original = prepare.overlay_bootstrap
    original_book_path = prepare.book_localization_addition
    original_books = prepare.book_localizations
    # The shared tool uses this set for pinned additions in both the overlay and its manifest verifier.
    prepare.LOGGING_FILES = prepare.LOGGING_FILES | {PRESET}
    prepare.book_localization_addition = lambda path: path == BOOKS or original_book_path(path)

    def verify_book_sources(recipe, pack_source, author):
        outputs = original_books(recipe, pack_source, author)
        if BOOKS in outputs:
            quest_root = "config/betterquesting/DefaultQuests"
            expected = {row["path"] for row in author["overrides"]
                        if row["path"] == quest_root + ".json" or row["path"].startswith(quest_root + "/")
                        and row["path"].endswith(".json")}
            dictionary = next(row for row in recipe["bookLocalization"] if row["path"] == BOOKS)
            actual = {path for path in dictionary["inputs"] if not path.startswith("mods/")}
            prepare.require(actual == expected, "Review the complete Better Questing source inventory")
        return outputs

    def verify_skin_bootstrap(recipe, pack_source, author, additions):
        bootstrap = recipe.get("bootstrap")
        if not isinstance(bootstrap, dict) or "authlib" not in bootstrap:
            return original(recipe, pack_source, author, additions)
        require = prepare.require
        require(set(bootstrap) == {"authlib", "artifact", "journeymap"} and bootstrap["authlib"] == AUTHLIB,
                "Review Nuclear Tech Reborn's exact Java 8 Authlib compatibility")
        dependency = {key: JOURNEYMAP[key] for key in ("projectId", "fileId")} | {"required": True}
        require(bootstrap["journeymap"] == JOURNEYMAP and dependency in author["declaredDependencies"],
                "Review JourneyMap's directory and server config hooks after an author mod update")
        if "files" in author:
            installed_maps = [row for row in author["files"] if row["path"] == JOURNEYMAP["path"]]
            require(len(installed_maps) == 1
                    and all(installed_maps[0].get(key) == value for key, value in JOURNEYMAP.items()),
                    "JourneyMap differs from the JAR used to verify the world-routing hooks")
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
    prepare.book_localizations = verify_book_sources


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
