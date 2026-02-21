#!/usr/bin/env python3
"""
Check consistency between pom.xml plugin version management and dependabot configuration.

Verifies:
1. All Maven plugin versions are declared in <pluginManagement> (no inline versions elsewhere).
2. All Maven dependency versions are declared in <dependencyManagement> (no inline versions elsewhere).
3. Every plugin groupId in <pluginManagement> is covered by the 'maven-plugins' dependabot group.
4. Every pattern in the 'maven-plugins' dependabot group covers at least one plugin groupId
   in <pluginManagement> (no stale patterns).
"""

import fnmatch
import re
import sys
import defusedxml.ElementTree as ET
from pathlib import Path

NS = "http://maven.apache.org/POM/4.0.0"
POM = Path("pom.xml")
DEPENDABOT = Path(".github/dependabot.yml")


def pm_plugin_ids(root):
    """Return the set of id()s for <plugin> elements inside <pluginManagement>."""
    ids = set()
    for pm in root.iter(f"{{{NS}}}pluginManagement"):
        for p in pm.iter(f"{{{NS}}}plugin"):
            ids.add(id(p))
    return ids


def dm_dependency_ids(root):
    """Return the set of id()s for <dependency> elements inside <dependencyManagement>."""
    ids = set()
    for dm in root.iter(f"{{{NS}}}dependencyManagement"):
        for d in dm.iter(f"{{{NS}}}dependency"):
            ids.add(id(d))
    return ids


def plugin_dependency_ids(root):
    """Return the set of id()s for <dependency> elements inside <plugin> (plugin-level deps).
    These are not governed by <dependencyManagement> so are excluded from that check."""
    ids = set()
    for plugin in root.iter(f"{{{NS}}}plugin"):
        for d in plugin.iter(f"{{{NS}}}dependency"):
            ids.add(id(d))
    return ids


def artifact_coords(element):
    gid = element.find(f"{{{NS}}}groupId")
    aid = element.find(f"{{{NS}}}artifactId")
    return (
        gid.text.strip() if gid is not None and gid.text else "unknown",
        aid.text.strip() if aid is not None and aid.text else "unknown",
    )


# Keep the old name as an alias used below
plugin_coords = artifact_coords


def extract_maven_plugins_patterns(text):
    """
    Extract the list of patterns under the 'maven-plugins' group in the maven
    package-ecosystem block of dependabot.yml.
    Returns a list of pattern strings, or an empty list if not found.
    """
    lines = text.splitlines()

    def find_line(start, pattern, stop_indent=None):
        """Find the first matching line after `start`, optionally stopping when
        indentation drops to or below `stop_indent`."""
        for i in range(start + 1, len(lines)):
            s = lines[i].strip()
            if not s or s.startswith("#"):
                continue
            indent = len(lines[i]) - len(lines[i].lstrip())
            if stop_indent is not None and indent <= stop_indent:
                return None
            if re.match(pattern, s):
                return i
        return None

    # Locate the maven ecosystem block
    maven_line = next(
        (i for i, l in enumerate(lines) if re.search(r"package-ecosystem:\s*maven\b", l)),
        None,
    )
    if maven_line is None:
        return []

    maven_indent = len(lines[maven_line]) - len(lines[maven_line].lstrip())

    groups_line = find_line(maven_line, r"groups:\s*$", stop_indent=maven_indent)
    if groups_line is None:
        return []

    groups_indent = len(lines[groups_line]) - len(lines[groups_line].lstrip())

    mp_line = find_line(groups_line, r"maven-plugins:\s*$", stop_indent=groups_indent)
    if mp_line is None:
        return []

    mp_indent = len(lines[mp_line]) - len(lines[mp_line].lstrip())

    patterns_line = find_line(mp_line, r"patterns:\s*$", stop_indent=mp_indent)
    if patterns_line is None:
        return []

    patterns_indent = len(lines[patterns_line]) - len(lines[patterns_line].lstrip())

    patterns = []
    for line in lines[patterns_line + 1 :]:
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= patterns_indent:
            break
        m = re.match(r"""-\s*["']?([^"'\s]+)["']?""", s)
        if m:
            patterns.append(m.group(1))

    return patterns


def covers_group_id(pattern, group_id):
    """Return True if the dependabot pattern covers the given plugin groupId.
    A pattern like 'org.foo:*' covers a groupId when it matches 'groupId:ANYTHING'."""
    return fnmatch.fnmatch(f"{group_id}:DUMMY", pattern)


def main():
    errors = []

    root = ET.parse(POM).getroot()
    dependabot_text = DEPENDABOT.read_text()

    # --- Check 1: no inline <version> outside <pluginManagement> ---
    pm_ids = pm_plugin_ids(root)
    for plugin in root.iter(f"{{{NS}}}plugin"):
        if id(plugin) in pm_ids:
            continue
        version = plugin.find(f"{{{NS}}}version")
        if version is not None and version.text:
            g, a = plugin_coords(plugin)
            errors.append(
                f"pom.xml: {g}:{a} has <version>{version.text.strip()}</version>"
                " defined outside <pluginManagement>"
            )

    # --- Check 2: no inline <version> outside <dependencyManagement> ---
    dm_ids = dm_dependency_ids(root)
    plugin_dep_ids = plugin_dependency_ids(root)
    for dep in root.iter(f"{{{NS}}}dependency"):
        if id(dep) in dm_ids or id(dep) in plugin_dep_ids:
            continue
        version = dep.find(f"{{{NS}}}version")
        if version is not None and version.text:
            g, a = artifact_coords(dep)
            errors.append(
                f"pom.xml: {g}:{a} has <version>{version.text.strip()}</version>"
                " defined outside <dependencyManagement>"
            )

    # --- Collect groupIds declared in <pluginManagement> ---
    pm_group_ids = set()
    for plugin in root.iter(f"{{{NS}}}plugin"):
        if id(plugin) not in pm_ids:
            continue
        gid = plugin.find(f"{{{NS}}}groupId")
        if gid is not None and gid.text:
            pm_group_ids.add(gid.text.strip())

    # --- Extract dependabot maven-plugins patterns ---
    patterns = extract_maven_plugins_patterns(dependabot_text)
    if not patterns:
        errors.append(
            "dependabot.yml: could not find patterns for the 'maven-plugins' group"
        )
    else:
        # --- Check 2: every <pluginManagement> groupId is covered ---
        for gid in sorted(pm_group_ids):
            if not any(covers_group_id(p, gid) for p in patterns):
                errors.append(
                    f"dependabot.yml: plugin groupId '{gid}' from <pluginManagement>"
                    " is not covered by any pattern in the 'maven-plugins' group"
                )

        # --- Check 3: no stale patterns ---
        for pattern in patterns:
            if not any(covers_group_id(pattern, gid) for gid in pm_group_ids):
                errors.append(
                    f"dependabot.yml: pattern '{pattern}' in the 'maven-plugins' group"
                    " does not match any plugin groupId in <pluginManagement>"
                )

    if errors:
        for e in errors:
            print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    print("OK: plugin/dependency versions and dependabot patterns are consistent")


if __name__ == "__main__":
    main()
