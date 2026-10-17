"""Create the deterministic local Maven fixture before either Gradle run."""

from pathlib import Path
from zipfile import ZipFile, ZipInfo


def main():
    repository = Path(__file__).resolve().parent / "build" / "fixture-repository"
    module = repository / "com" / "example" / "demo-library"
    versions = ("1.0.0", "1.1.0", "2.0.0")
    for version in versions:
        directory = module / version
        directory.mkdir(parents=True, exist_ok=True)
        directory.joinpath(f"demo-library-{version}.pom").write_text(
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
            f"<artifactId>demo-library</artifactId><version>{version}</version></project>\n",
            encoding="utf-8",
        )
        with ZipFile(directory / f"demo-library-{version}.jar", "w") as archive:
            archive.writestr(
                ZipInfo("META-INF/MANIFEST.MF", date_time=(1980, 1, 1, 0, 0, 0)),
                "Manifest-Version: 1.0\n\n",
            )
    module.joinpath("maven-metadata.xml").write_text(
        "<metadata><groupId>com.example</groupId><artifactId>demo-library</artifactId>"
        "<versioning><latest>2.0.0</latest><release>2.0.0</release><versions>"
        + "".join(f"<version>{version}</version>" for version in versions)
        + "</versions></versioning></metadata>\n",
        encoding="utf-8",
    )
    print(f"Prepared com.example:demo-library ({', '.join(versions)}) in {repository}")


if __name__ == "__main__":
    main()
