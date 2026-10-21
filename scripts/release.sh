#!/bin/sh
# Start the tag-driven release workflow. Verification and publication run in CI.
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if [ "$#" -ne 1 ]; then
    echo "Usage: ./scripts/release.sh X.Y.Z" >&2
    exit 1
fi
python3 scripts/release-version.py "$1"
version=$1
tag="v$version"

fail() {
    echo "Error: $*" >&2
    exit 1
}

[ "$(git symbolic-ref --quiet --short HEAD)" = main ] || fail "Switch to main before releasing."
[ -z "$(git status --porcelain)" ] || fail "Commit or stash all changes before releasing."

echo "Checking origin/main and release tag $tag..."
git fetch --no-tags origin '+refs/heads/main:refs/remotes/origin/main'
commit=$(git rev-parse HEAD)
[ "$commit" = "$(git rev-parse refs/remotes/origin/main)" ] || fail "Local main must match origin/main. Pull or push your commits first."
if git show-ref --verify --quiet "refs/tags/$tag"; then
    fail "Local tag $tag already exists."
fi
remote_tags=$(git ls-remote --refs --tags origin)
printf '%s\n' "$remote_tags" | python3 scripts/release-version.py "$version" --check-remote-tags

if python3 scripts/release-version.py "$version" --update-examples; then
    :
else
    status=$?
    if [ "$status" -eq 3 ]; then
        git add -- examples/simple-java/build.gradle examples/multi-module/build.gradle \
            examples/kotlin-dsl/build.gradle.kts .github/workflows/build.yml scripts/ci-matrix/run.py
        git commit -m "chore: prepare release $version"
    else
        fail "Could not update example and CI versions; no release tag was created."
    fi
fi

commit=$(git rev-parse HEAD)
git tag -a "$tag" "$commit" -m "Release $version"
if ! git push --atomic origin "$commit:refs/heads/main" "refs/tags/$tag:refs/tags/$tag"; then
    fail "Could not confirm the atomic release push. Local tag $tag was retained. Check origin/main, the remote tag and Actions before retrying: git push --atomic origin $commit:refs/heads/main refs/tags/$tag:refs/tags/$tag"
fi
echo "Release $version triggered for commit $commit."
echo "Follow verification and publication in GitHub Actions (Release workflow)."
