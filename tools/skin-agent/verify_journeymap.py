"""Check the built bootstrap against the exact Forge 1.12.2 runtime JARs without starting Minecraft."""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile


LIBRARIES = (
    "net.minecraftforge:forge:1.12.2-14.23.5.2859",
    "net.minecraft:launchwrapper:1.12",
    "org.ow2.asm:asm-debug-all:5.2",
    "com.google.guava:guava:21.0",
    "com.google.code.gson:gson:2.8.0",
    "net.sf.jopt-simple:jopt-simple:5.0.3",
    "org.apache.logging.log4j:log4j-api:2.15.0",
    "org.apache.logging.log4j:log4j-core:2.15.0",
    "lzma:lzma:0.0.1",
    "commons-io:commons-io:2.5",
)
LOGGING = """<Configuration status="ERROR">
  <Appenders><Console name="Console" /></Appenders>
  <Loggers><Root level="warn"><AppenderRef ref="Console" /></Root></Loggers>
</Configuration>
"""


def library_path(shared, coordinate):
    group, artifact, version = coordinate.split(":")
    return shared / "libraries" / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java8", required=True, type=Path, help="Path to Java 8 java.exe")
    parser.add_argument("--javac", required=True, type=Path, help="Modern javac.exe supporting --release 8")
    parser.add_argument("--agent", required=True, type=Path)
    parser.add_argument("--journeymap", required=True, type=Path)
    parser.add_argument("--e4steam", required=True, type=Path)
    parser.add_argument("--shared", required=True, type=Path, help="Launcher Shared libraries and versions directory")
    parser.add_argument("--run", required=True, type=Path, help="Owned artifacts/agent-runs run containing RUN.json")
    args = parser.parse_args()
    run = args.run.resolve()
    if run.parent.name != "agent-runs" or not (run / "RUN.json").is_file():
        parser.error("--run must be an owned agent-runs directory")

    client = args.shared.resolve() / "versions/1.12.2/1.12.2.jar"
    libraries = [library_path(args.shared.resolve(), coordinate) for coordinate in LIBRARIES]
    for path in [args.java8, args.javac, args.agent, args.journeymap, args.e4steam, client, *libraries]:
        if not path.is_file():
            parser.error(f"Required input does not exist: {path}")

    for jar in (args.agent, args.journeymap, args.e4steam, client, libraries[0]):
        with jar.open("rb") as source:
            print(f"SHA256 {hashlib.file_digest(source, 'sha256').hexdigest()} {jar.name}", flush=True)

    source = Path(__file__).with_name("Verify-JourneyMapRouting.java").resolve()
    with tempfile.TemporaryDirectory(prefix="journeymap-probe-", dir=run) as staging:
        scratch = Path(staging).resolve()
        if scratch.parent != run:
            raise RuntimeError("Probe staging resolved outside its run directory")
        classes = scratch / "classes"
        classes.mkdir()
        fixture = scratch / "fixture"
        fixture.mkdir()
        logging = scratch / "log4j2.xml"
        logging.write_text(LOGGING, encoding="utf-8")
        classpath = os.pathsep.join(map(str, libraries))
        subprocess.run(
            [str(args.javac.resolve()), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
             "-classpath", classpath, "-d", str(classes), str(source)],
            check=True,
            cwd=scratch,
        )
        subprocess.run(
            [str(args.java8.resolve()), "-Xverify:all", f"-javaagent:{args.agent.resolve()}",
             f"-Dlog4j.configurationFile={logging.as_uri()}",
             "-classpath", f"{classes}{os.pathsep}{classpath}", "JourneyMapRoutingProbe",
             str(args.journeymap.resolve()), str(args.e4steam.resolve()), str(client), str(fixture)],
            check=True,
            cwd=scratch,
        )


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        raise SystemExit(failure.returncode) from failure
