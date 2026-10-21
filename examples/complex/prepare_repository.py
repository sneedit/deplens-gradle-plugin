# SPDX-FileCopyrightText: 2026 Sneed IT
# SPDX-License-Identifier: Apache-2.0

"""Create the deterministic local Maven fixture before either Gradle run."""

from pathlib import Path
from zipfile import ZipFile, ZipInfo


def main():
    repository = Path(__file__).resolve().parent / "build" / "fixture-repository"
    module = repository / "com" / "example" / "eventlog" / "streams-compat"
    versions = (
        "0.11.0.0", "0.11.0.3", "0.12.0.0",
        "1.0.0", "1.0.2", "1.1.0",
        "2.0.0", "2.0.1", "2.1.0", "2.8.0", "2.8.2", "2.9.0",
        "3.9.0", "3.9.2", "3.10.0", "4.0.0", "4.0.2", "4.1.0", "5.0.0",
    )
    for version in versions:
        directory = module / version
        directory.mkdir(parents=True, exist_ok=True)
        directory.joinpath(f"streams-compat-{version}.pom").write_text(
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.example.eventlog</groupId>"
            f"<artifactId>streams-compat</artifactId><version>{version}</version></project>\n",
            encoding="utf-8",
        )
        with ZipFile(directory / f"streams-compat-{version}.jar", "w") as archive:
            archive.writestr(
                ZipInfo("META-INF/MANIFEST.MF", date_time=(1980, 1, 1, 0, 0, 0)),
                "Manifest-Version: 1.0\n\n",
            )
    module.joinpath("maven-metadata.xml").write_text(
        "<metadata><groupId>com.example.eventlog</groupId><artifactId>streams-compat</artifactId>"
        "<versioning><latest>5.0.0</latest><release>5.0.0</release><versions>"
        + "".join(f"<version>{version}</version>" for version in versions)
        + "</versions></versioning></metadata>\n",
        encoding="utf-8",
    )
    print(f"Prepared com.example.eventlog:streams-compat ({', '.join(versions)}) in {repository}")


if __name__ == "__main__":
    main()
