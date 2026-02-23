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


def read_stored_version(subpath):
    try:
        with open(os.path.join(STORE, subpath, ".version")) as f:
            return f.read().strip()
    except OSError:
        return None


version_dirs = sorted(
    [e.name for e in os.scandir(STORE) if e.is_dir() and re.match(r"^\d", e.name)],
    key=version_key,
    reverse=True,
)

sections = []

# Latest release card
has_latest_plugin = os.path.isdir(os.path.join(STORE, "latest", "maven-plugin"))
has_latest_apidocs = os.path.isdir(os.path.join(STORE, "latest", "api-javadoc"))
has_latest_manpage = os.path.isdir(os.path.join(STORE, "latest", "cli-manpage"))
if has_latest_plugin or has_latest_apidocs or has_latest_manpage:
    version = read_stored_version("latest") or (version_dirs[0] if version_dirs else "")
    links = []
    if has_latest_plugin:
        links.append(
            '    <a class="big" href="latest/maven-plugin/">Maven Plugin Docs &#8594;</a>'
        )
    if has_latest_apidocs:
        links.append(
            '    <a class="big" href="latest/api-javadoc/">API Javadoc &#8594;</a>'
        )
    if has_latest_manpage:
        links.append(
            '    <a class="big" href="latest/cli-manpage/codesign.html">CLI Man Page &#8594;</a>'
        )
    sections.append(LATEST_SECTION.format(version=version, links="\n".join(links)))

# Snapshot card
has_snapshot_plugin = os.path.isdir(os.path.join(STORE, "snapshot", "maven-plugin"))
has_snapshot_apidocs = os.path.isdir(os.path.join(STORE, "snapshot", "api-javadoc"))
has_snapshot_manpage = os.path.isdir(os.path.join(STORE, "snapshot", "cli-manpage"))
if has_snapshot_plugin or has_snapshot_apidocs or has_snapshot_manpage:
    version = read_stored_version("snapshot") or ""
    links = []
    if has_snapshot_plugin:
        links.append(
            '    <a class="big snap" href="snapshot/maven-plugin/">Maven Plugin Docs &#8594;</a>'
        )
    if has_snapshot_apidocs:
        links.append(
            '    <a class="big snap" href="snapshot/api-javadoc/">API Javadoc &#8594;</a>'
        )
    if has_snapshot_manpage:
        links.append(
            '    <a class="big snap" href="snapshot/cli-manpage/codesign.html">CLI Man Page &#8594;</a>'
        )
    sections.append(SNAPSHOT_SECTION.format(version=version, links="\n".join(links)))

# All releases card
if version_dirs:
    items = []
    for v in version_dirs:
        link_parts = []
        if os.path.isdir(os.path.join(STORE, v, "maven-plugin")):
            link_parts.append(f'<a href="{v}/maven-plugin/">Maven Plugin Docs</a>')
        if os.path.isdir(os.path.join(STORE, v, "api-javadoc")):
            link_parts.append(f'<a href="{v}/api-javadoc/">API Javadoc</a>')
        if os.path.isdir(os.path.join(STORE, v, "cli-manpage")):
            link_parts.append(
                f'<a href="{v}/cli-manpage/codesign.html">CLI Man Page</a>'
            )
        if link_parts:
            items.append(
                f"      <li><strong>v{v}</strong> &mdash; {' &bull; '.join(link_parts)}</li>"
            )
    if items:
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

print("Generated index.html with %d release(s)" % len(version_dirs))
