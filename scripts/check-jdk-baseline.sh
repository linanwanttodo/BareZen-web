#!/usr/bin/env bash
# Guard the JDK 17 compatibility baseline (ADR-0002).
#
# Fails if any module POM overrides the compiler release set by barezen-parent, if the parent stops
# declaring 17, or if a JDK-specific module directory reappears. Run from CI and from a pre-commit
# hook, so the baseline cannot drift because someone's laptop has a newer JDK.
set -euo pipefail

cd "$(dirname "$0")/.."
status=0

expected_property='<maven.compiler.release>17</maven.compiler.release>'
if ! grep -qF "$expected_property" pom.xml; then
  echo "FAIL: barezen-parent must declare ${expected_property}" >&2
  status=1
fi

# A child module overriding the release is the exact drift this script exists to catch. XML comments
# are stripped first, because the Poms document the rule by naming the tag.
while IFS= read -r pom; do
  if python3 -c '
import re, sys
text = re.sub(r"<!--.*?-->", "", open(sys.argv[1]).read(), flags=re.S)
sys.exit(0 if re.search(r"<maven\.compiler\.release>|<release>", text) else 1)
' "$pom"; then
    echo "FAIL: ${pom} overrides the compiler release; JDK 17 is owned by barezen-parent" >&2
    status=1
  fi
done < <(find . -name pom.xml -not -path './pom.xml' -not -path '*/target/*')

# JDK-specific modules were rejected by ADR-0002; newer JDK features are a runtime capability.
if find . -maxdepth 3 -type d \( -name 'barezen-jdk*' -o -name 'barezen-runtime' \) | grep -q .; then
  echo "FAIL: JDK-specific module directory found; see ADR-0002 and ADR-0008" >&2
  find . -maxdepth 3 -type d \( -name 'barezen-jdk*' -o -name 'barezen-runtime' \) >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "OK: JDK 17 baseline intact ($(javac -version 2>&1))"
fi
exit "$status"
