#!/usr/bin/env bash
# Guard the emitted bytecode, not the declared release (ADR-0032).
#
# scripts/check-jdk-baseline.sh asserts that every module inherits `maven.compiler.release=17`. This script asserts
# what that declaration is supposed to produce: every class file the reactor emitted has class-file major version 61,
# the version JDK 17 reads. It is the other half of one contract, and the half that catches a build configured
# correctly which produced something else anyway — a `-Dmaven.compiler.release=21` left on the command line, a
# compiler plugin re-bound to a newer release, a class copied in from somewhere else.
#
# It runs against whatever the last build left in `target/`, so CI calls it after `mvn clean verify` and before the
# job may be called green. It reads bytes only: no JDK, no Maven, no network, so it is offline-safe and identical on
# every machine, and it does not need the JDK that produced the output to be the one running it.
#
# Scope, stated rather than implied (ADR-0032 §Decision): `target/classes` and `target/test-classes` of every module
# that has sources. A packaged jar is a copy of `target/classes`, so reading the entries inside it adds no evidence;
# a class file anywhere else — under another `target/` directory, or outside `target/` entirely — is a build output
# nobody asserted, so it fails the check instead of being silently skipped.
set -euo pipefail

cd "$(dirname "$0")/.."
expected_major=61
failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# Offset 6 of a class file holds its major version, big-endian. The two bytes are read as bytes and combined by
# arithmetic rather than trusting `od -tu2`, whose interpretation of a 2-byte word is machine-dependent.
major_of() {
  od -An -tu1 -j6 -N2 -- "$1" | awk '{ printf "%d", $1 * 256 + $2 }'
}

magic_of() {
  od -An -tx1 -N4 -- "$1" | tr -d ' \t\n'
}

# One sweep for every class file in the tree, so the checked count is a measured number rather than a guess about
# which modules were built.
class_files=()
while IFS= read -r file; do
  class_files+=("$file")
done < <(find . -type f -name '*.class' -print | sort)

if [ "${#class_files[@]}" -eq 0 ]; then
  echo "FAIL: no class file found in the tree; run the build before the guard" >&2
  exit 1
fi

declare -A per_dir=()
majors=()
checked=0
for file in "${class_files[@]}"; do
  # A class file sits under <module>/target/{classes,test-classes}/<package path>, so the two asserted outputs are
  # identified by the first path element after `target/`, not by the file's own directory.
  module="${file%/target/*}"
  after="${file#*/target/}"
  top="${after%%/*}"
  case "$top" in
    classes | test-classes) ;;
    *)
      fail "${file} is not under target/classes or target/test-classes"
      continue
      ;;
  esac
  checked=$((checked + 1))
  per_dir["$module/target/$top"]=$(( ${per_dir["$module/target/$top"]:-0} + 1 ))

  magic=$(magic_of "$file")
  if [ "$magic" != 'cafebabe' ]; then
    fail "${file} is not a class file (magic ${magic})"
    continue
  fi
  majors+=("$(major_of "$file")")
done

unique=$(printf '%s\n' "${majors[@]}" | sort -n -u | tr '\n' ' ' | sed 's/ $//')

# A module with sources that emitted nothing means this guard looked at the wrong tree. A guard that can pass on zero
# files is not a guard, so each expected output directory is asserted non-empty by name.
for src in $(find . -type d -path '*/src/main/java' -not -path '*/target/*' -print | sort); do
  module="${src%/src/main/java}"
  count="${per_dir["$module/target/classes"]:-0}"
  printf '  %-36s classes=%s\n' "${module#./}" "$count"
  [ "$count" -gt 0 ] || fail "${module} has main sources but emitted no class file into target/classes"
done
for src in $(find . -type d -path '*/src/test/java' -not -path '*/target/*' -print | sort); do
  module="${src%/src/test/java}"
  count="${per_dir["$module/target/test-classes"]:-0}"
  printf '  %-36s test-classes=%s\n' "${module#./}" "$count"
  [ "$count" -gt 0 ] || fail "${module} has test sources but emitted no class file into target/test-classes"
done

printf 'checked=%d unique-majors=[%s] expected=%d\n' "$checked" "$unique" "$expected_major"

if [ "$unique" != "$expected_major" ]; then
  fail "emitted class files are not all major ${expected_major}; every module must compile with --release 17"
fi

if [ "$failures" -ne 0 ]; then
  echo "FAIL: ${failures} class-major violation(s)" >&2
  exit 1
fi

echo "OK: every emitted class file is major ${expected_major} across ${checked} files"
