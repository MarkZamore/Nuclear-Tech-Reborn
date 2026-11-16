"""Exercise the pack agent with stock and Forge-rewritten Authlib on Java 8."""
import argparse
import hashlib
from pathlib import Path
import subprocess


libraries = (
    "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
    "org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar",
    "org/apache/logging/log4j/log4j-api/2.15.0/log4j-api-2.15.0.jar",
    "com/google/guava/guava/21.0/guava-21.0.jar",
    "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar",
    "org/apache/commons/commons-lang3/3.5/commons-lang3-3.5.jar",
    "commons-io/commons-io/2.5/commons-io-2.5.jar",
    "commons-codec/commons-codec/1.10/commons-codec-1.10.jar",
)
forge_jar = "net/minecraftforge/forge/1.12.2-14.23.5.2859/forge-1.12.2-14.23.5.2859.jar"
authlib_jar = "com/mojang/authlib/1.5.25/authlib-1.5.25.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("launcher", type=Path)
    parser.add_argument("agent", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    launcher = args.launcher.resolve()
    agent = args.agent.resolve(strict=True)
    output = args.output.resolve()
    if (output.parent.parent.name != "agent-runs" or not (output.parent / "RUN.json").is_file()):
        raise ValueError("Use a fresh child directory of an owned run")
    library = launcher / "Shared/libraries"
    forge, authlib = library / forge_jar, library / authlib_jar
    for path, digest in (
        (forge, "bd8314729d787e35164476d75afb337afb9783917e4f6fa1f77f049adba22f2d"),
        (authlib, "c8a095123be93c59d4ac29e4a0616495b3d479ac65913cb719a81ee583e9dfcd"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Unexpected runtime input {path.name}")
    java = launcher / "JavaRuntimes/runtime/windows-x64/java-8/bin"
    output.mkdir()
    classpath = ";".join(str(path) for path in (output, forge, *(library / p for p in libraries)))
    source = Path(__file__).resolve().with_name("Verify-LegacySkin.java")
    subprocess.run([java / "javac.exe", "-encoding", "UTF-8", "-cp", classpath, "-d", output, source],
                   cwd=output, check=True)
    owner = "11111111-1111-4111-8111-111111111111"
    other = "22222222-2222-4222-8222-222222222222"
    tunnel = "33333333-3333-3333-8333-333333333333"
    ambiguous = "44444444-4444-3444-8444-444444444444"
    pin = "a" * 64
    url = f"http://127.0.0.1:51111/skin/{owner}/{pin}"
    skins, identities = output / "skins.properties", output / "identity.properties"
    skins.write_text(f"{owner}|{pin}|slim|{url}\n", encoding="utf-8")
    identities.write_text(
        f"DifferentName|{owner}|{tunnel}\nDuplicateOne|{owner}|{ambiguous}\nDuplicateTwo|{other}|{ambiguous}\n",
        encoding="utf-8",
    )
    for mode in ("stock", "forge"):
        print(mode, flush=True)
        subprocess.run(
            [java / "java.exe", "-Xverify:all", "-Xmx128m", f"-javaagent:{agent}",
             f"-Dminecraft.portable.skin.registry={skins}", f"-Dminecraft.portable.identity.registry={identities}",
             "-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory",
             "-cp", classpath, "LegacySkinProbe", authlib, owner, url, mode, tunnel, ambiguous],
            cwd=output, check=True, timeout=20,
        )


if __name__ == "__main__":
    main()
