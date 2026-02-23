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
    <h2>Latest Release</h2>
    <p>Documentation for the most recent stable release (v{version}).</p>
    <a class="big" href="latest/">Latest &#8594;</a>
  </div>"""

SNAPSHOT_SECTION = """\
  <div class="card">
    <h2>Snapshot</h2>
    <p>Documentation built from the <code>main</code> branch ({version}, may be unstable).</p>
    <a class="big snap" href="snapshot/">Snapshot &#8594;</a>
  </div>"""

ALL_RELEASES_SECTION = """\
  <div class="card">
    <h2>All Releases</h2>
    <ul>
{items}
    </ul>
  </div>"""

RELEASE_ITEM = '      <li><a href="{v}/">v{v}</a></li>'

APIDOCS_LATEST_SECTION = """\
  <div class="card">
    <h2>API Javadoc &ndash; Latest Release</h2>
    <p>Javadoc for the most recent stable release (v{version}).</p>
    <a class="big" href="apidocs/latest/">Latest API Docs &#8594;</a>
  </div>"""

APIDOCS_SNAPSHOT_SECTION = """\
  <div class="card">
    <h2>API Javadoc &ndash; Snapshot</h2>
    <p>Javadoc built from the <code>main</code> branch ({version}, may be unstable).</p>
    <a class="big snap" href="apidocs/snapshot/">Snapshot API Docs &#8594;</a>
  </div>"""

APIDOCS_ALL_RELEASES_SECTION = """\
  <div class="card">
    <h2>API Javadoc &ndash; All Releases</h2>
    <ul>
{items}
    </ul>
  </div>"""

APIDOCS_RELEASE_ITEM = '      <li><a href="apidocs/{v}/">v{v}</a></li>'


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


versions = sorted(
    [e.name for e in os.scandir(STORE) if e.is_dir() and re.match(r"^\d", e.name)],
    key=version_key,
    reverse=True,
)

sections = []

if os.path.isdir(os.path.join(STORE, "latest")):
    latest_version = read_stored_version("latest") or (versions[0] if versions else "")
    sections.append(LATEST_SECTION.format(version=latest_version))

if os.path.isdir(os.path.join(STORE, "snapshot")):
    snapshot_version = read_stored_version("snapshot") or ""
    sections.append(SNAPSHOT_SECTION.format(version=snapshot_version))

if versions:
    items = "\n".join(RELEASE_ITEM.format(v=v) for v in versions)
    sections.append(ALL_RELEASES_SECTION.format(items=items))

apidocs_dir = os.path.join(STORE, "apidocs")
if os.path.isdir(apidocs_dir):
    apidocs_versions = sorted(
        [e.name for e in os.scandir(apidocs_dir) if e.is_dir() and re.match(r"^\d", e.name)],
        key=version_key,
        reverse=True,
    )
else:
    apidocs_versions = []

if os.path.isdir(os.path.join(apidocs_dir, "latest")):
    latest_version = read_stored_version("apidocs/latest") or (apidocs_versions[0] if apidocs_versions else "")
    sections.append(APIDOCS_LATEST_SECTION.format(version=latest_version))

if os.path.isdir(os.path.join(apidocs_dir, "snapshot")):
    snapshot_version = read_stored_version("apidocs/snapshot") or ""
    sections.append(APIDOCS_SNAPSHOT_SECTION.format(version=snapshot_version))

if apidocs_versions:
    items = "\n".join(APIDOCS_RELEASE_ITEM.format(v=v) for v in apidocs_versions)
    sections.append(APIDOCS_ALL_RELEASES_SECTION.format(items=items))

with open(os.path.join(SCRIPT_DIR, "site-index-template.html")) as f:
    template = Template(f.read())

html = template.substitute(
    name=read_pom_field("name"),
    description=read_pom_field("description"),
    sections="\n".join(sections),
)

with open(os.path.join(STORE, "index.html"), "w") as f:
    f.write(html)

print("Generated index.html with %d release(s)" % len(versions))
