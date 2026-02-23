#!/usr/bin/env python3
"""Generate the root index.html for the gh-pages site, listing all doc versions."""

import os
import re
import defusedxml.ElementTree as ET
from string import Template

STORE = "target/gh-pages-store"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

LATEST_SECTION = """\
  <div class="card">
    <h2>Latest Release (v{version})</h2>
{links}
  </div>"""

SNAPSHOT_SECTION = """\
  <div class="card">
    <h2>Snapshot ({version}, may be unstable)</h2>
{links}
  </div>"""

ALL_RELEASES_SECTION = """\
  <div class="card">
    <h2>All Releases</h2>
    <ul>
{items}
    </ul>
  </div>"""


def version_key(v):
    return tuple((0, int(x)) if x.isdigit() else (1, x) for x in re.split(r"[.\-]", v))


def read_pom_field(tag, pom_path="pom.xml"):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    root = ET.parse(pom_path).getroot()
    el = root.find(f"m:{tag}", ns)
    return el.text.strip() if el is not None else ""


def read_stored_version(directory):
    try:
        with open(os.path.join(STORE, directory, ".version")) as f:
            return f.read().strip()
    except OSError:
        return None


apidocs_dir = os.path.join(STORE, "apidocs")

plugin_versions = sorted(
    [e.name for e in os.scandir(STORE) if e.is_dir() and re.match(r"^\d", e.name)],
    key=version_key,
    reverse=True,
)

apidocs_versions = (
    sorted(
        [
            e.name
            for e in os.scandir(apidocs_dir)
            if e.is_dir() and re.match(r"^\d", e.name)
        ],
        key=version_key,
        reverse=True,
    )
    if os.path.isdir(apidocs_dir)
    else []
)

all_versions = sorted(
    set(plugin_versions) | set(apidocs_versions),
    key=version_key,
    reverse=True,
)

sections = []

# Latest release card
has_latest_plugin = os.path.isdir(os.path.join(STORE, "latest"))
has_latest_apidocs = os.path.isdir(os.path.join(apidocs_dir, "latest"))
if has_latest_plugin or has_latest_apidocs:
    version = (
        read_stored_version("latest")
        or read_stored_version("apidocs/latest")
        or (all_versions[0] if all_versions else "")
    )
    links = []
    if has_latest_plugin:
        links.append('    <a class="big" href="latest/">Maven Plugin Docs &#8594;</a>')
    if has_latest_apidocs:
        links.append(
            '    <a class="big" href="apidocs/latest/">API Javadoc &#8594;</a>'
        )
    sections.append(LATEST_SECTION.format(version=version, links="\n".join(links)))

# Snapshot card
has_snapshot_plugin = os.path.isdir(os.path.join(STORE, "snapshot"))
has_snapshot_apidocs = os.path.isdir(os.path.join(apidocs_dir, "snapshot"))
if has_snapshot_plugin or has_snapshot_apidocs:
    version = (
        read_stored_version("snapshot") or read_stored_version("apidocs/snapshot") or ""
    )
    links = []
    if has_snapshot_plugin:
        links.append(
            '    <a class="big snap" href="snapshot/">Maven Plugin Docs &#8594;</a>'
        )
    if has_snapshot_apidocs:
        links.append(
            '    <a class="big snap" href="apidocs/snapshot/">API Javadoc &#8594;</a>'
        )
    sections.append(SNAPSHOT_SECTION.format(version=version, links="\n".join(links)))

# All releases card
if all_versions:
    items = []
    for v in all_versions:
        link_parts = []
        if v in plugin_versions:
            link_parts.append(f'<a href="{v}/">Maven Plugin Docs</a>')
        if v in apidocs_versions:
            link_parts.append(f'<a href="apidocs/{v}/">API Javadoc</a>')
        items.append(
            f"      <li><strong>v{v}</strong> &mdash; {' &bull; '.join(link_parts)}</li>"
        )
    sections.append(ALL_RELEASES_SECTION.format(items="\n".join(items)))

with open(os.path.join(SCRIPT_DIR, "site-index-template.html")) as f:
    template = Template(f.read())

html = template.substitute(
    name=read_pom_field("name"),
    description=read_pom_field("description"),
    sections="\n".join(sections),
)

with open(os.path.join(STORE, "index.html"), "w") as f:
    f.write(html)

print("Generated index.html with %d release(s)" % len(all_versions))
