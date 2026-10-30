#!/usr/bin/env bash
# Checks a minified (R8) APK for what reflection needs and R8 can't always see: every class Android
# or a library creates by name must be in the dex, public, with a public no-argument constructor.
#   - the manifest's application, activities, services, receivers and providers;
#   - androidx.startup initializers (meta-data with the value "androidx.startup");
#   - ServiceLoader implementations (META-INF/services/*).
# Ferb's release 0.1 lost a manifest-named class's constructor under R8 full mode (2026-10-30);
# this is the same check for Ferb Link, run by release.sh before signing.
# Usage: release/check-reflection.sh APK
set -euo pipefail
APK=${1:?usage: check-reflection.sh APK}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; SDK=${SDK/#\~/$HOME}
TOOLS=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Manifest components (android:name on application/activity/service/receiver/provider) and
# androidx.startup initializers. Plain-name attributes elsewhere (permissions, actions) are skipped.
"$TOOLS/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | awk '
  /E: (application|activity|activity-alias|service|receiver|provider) / { want = 1; next }
  /E: meta-data / { meta = 1; name = ""; next }
  /E: / { want = 0; meta = 0 }
  want && /android:name\(0x01010003\)=/ { match($0, /="[^"]*"/); print substr($0, RSTART + 2, RLENGTH - 3); want = 0 }
  meta && /android:name\(0x01010003\)=/ { match($0, /="[^"]*"/); name = substr($0, RSTART + 2, RLENGTH - 3) }
  meta && /android:value\(0x01010024\)="androidx.startup"/ { print name; meta = 0 }
' | sort -u > "$WORK/classes"

unzip -q -o "$APK" 'classes*.dex' -d "$WORK"

# ServiceLoader implementations, as R8 rewrote them, but only if the code calls ServiceLoader at
# all: R8 turns coroutines' Main dispatcher lookup into a plain constructor call and leaves the
# services files behind, unread (Ferb Link 0.1: both files are leftovers).
if grep -aqs 'Ljava/util/ServiceLoader;' "$WORK"/classes*.dex; then
  unzip -Z1 "$APK" 'META-INF/services/*' 2>/dev/null | while read -r f; do
    unzip -p "$APK" "$f" | sed 's/#.*//' | tr -d ' \r' | grep -v '^$' || true
  done >> "$WORK/classes"
else
  echo "Skipped: META-INF/services (nothing in the dex calls ServiceLoader)"
fi
sort -u -o "$WORK/classes" "$WORK/classes"
[ -s "$WORK/classes" ] || { echo "FAIL: found no classes to check in $APK"; exit 1; }

for dex in "$WORK"/classes*.dex; do "$TOOLS/dexdump" "$dex" 2>/dev/null; done > "$WORK/dex.txt"

fail=0
while read -r class; do
  descriptor="L${class//.//};"
  # The class's block: public itself, with a public <init> of type ()V among its direct methods.
  if awk -v d="$descriptor" '
      /Class descriptor/ { inside = index($0, "\x27" d "\x27") > 0; if (inside) seen = 1; classAccess = 0; next }
      inside && !classAccess && /^  Access flags/ { classAccess = 1; if ($0 ~ /PUBLIC/) pub = 1 }
      inside && /name          : \x27<init>\x27/ { init = 1; next }
      inside && init && /type          : \x27\(\)V\x27/ { ctor = 1 }
      inside && init && /access        :/ { if (ctor && $0 ~ /PUBLIC/) found = 1; init = 0; ctor = 0 }
      END { exit (seen && pub && found) ? 0 : 1 }' "$WORK/dex.txt"; then
    echo "Kept: $class, public, with a public no-argument constructor"
  else
    echo "FAIL: $class (created by name) is missing, not public, or has no public no-argument constructor in $APK: check companion/app/proguard-rules.pro"
    fail=1
  fi
done < "$WORK/classes"
exit $fail
