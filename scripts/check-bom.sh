#!/usr/bin/env bash
#
# Fails when notification-service-bom/pom.xml and the reactor disagree:
#   - a jar module of the reactor is missing from the BOM's dependencyManagement,
#   - the BOM lists an artifact that is not a jar module of the reactor,
#   - a BOM entry is not at ${project.version},
#   - the BOM's own version differs from the root pom's version.
#
# Usage: scripts/check-bom.sh [repository-root]   (default: the parent of this script's directory)
# Needs python3 (standard library only).

set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

python3 - "$ROOT" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1]
BOM_DIR = "notification-service-bom"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Jar modules that are deliberately not in the BOM, with the reason.
EXCLUDED = {
    "notification-server": "the standalone application, not a library to depend on",
}


def text(element, path):
    found = element.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def jar_modules(module_dir):
    """Yield (artifactId, path) for every jar-packaged module under module_dir, recursively."""
    pom = ET.parse(os.path.join(module_dir, "pom.xml")).getroot()
    packaging = text(pom, "m:packaging") or "jar"
    if packaging == "jar":
        yield text(pom, "m:artifactId"), os.path.relpath(module_dir, ROOT)
    for module in pom.findall("m:modules/m:module", NS):
        yield from jar_modules(os.path.join(module_dir, module.text.strip()))


root_pom = ET.parse(os.path.join(ROOT, "pom.xml")).getroot()
bom_pom = ET.parse(os.path.join(ROOT, BOM_DIR, "pom.xml")).getroot()
errors = []

if BOM_DIR not in [m.text.strip() for m in root_pom.findall("m:modules/m:module", NS)]:
    errors.append(f"{BOM_DIR} is not listed in the root pom's <modules>, so releases would not version it")

root_version = text(root_pom, "m:version")
bom_version = text(bom_pom, "m:version")
if bom_version != root_version:
    errors.append(f"{BOM_DIR} version {bom_version} differs from the reactor version {root_version}")

modules = {artifact: path for artifact, path in jar_modules(ROOT)}
expected = {artifact for artifact in modules if artifact not in EXCLUDED}

listed = {}
for dependency in bom_pom.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
    listed[text(dependency, "m:artifactId")] = (text(dependency, "m:groupId"), text(dependency, "m:version"))

for artifact in sorted(expected - listed.keys()):
    errors.append(f"jar module {artifact} ({modules[artifact]}) is missing from {BOM_DIR}/pom.xml")
for artifact in sorted(listed.keys() - expected):
    reason = EXCLUDED.get(artifact, "no jar module of the reactor has this artifactId")
    errors.append(f"{BOM_DIR}/pom.xml lists {artifact}, which it should not: {reason}")
for artifact, (group, version) in sorted(listed.items()):
    if group != "com.github.ifrugal":
        errors.append(f"{BOM_DIR}/pom.xml lists {artifact} with groupId {group}, expected com.github.ifrugal")
    if version != "${project.version}":
        errors.append(f"{BOM_DIR}/pom.xml lists {artifact} at {version}, expected ${{project.version}}")

if errors:
    print("BOM check failed:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    sys.exit(1)

print(f"BOM check passed: {len(listed)} artifacts at version {bom_version}; "
      f"excluded on purpose: {', '.join(sorted(EXCLUDED))}")
PY
