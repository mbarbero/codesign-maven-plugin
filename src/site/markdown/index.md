# CSI Codesign Maven Plugin

Maven plugin that signs artifacts via the [SignPath](https://about.signpath.io/) REST API.

## Goals Overview

The plugin provides 1 goal to sign Maven build artifacts:

* [codesign:sign](./sign-mojo.html) submits build artifacts to the SignPath REST API for signing
  and downloads the signed files once the signing request completes.

## Usage

General instructions on how to use the CSI Codesign Maven Plugin can be found on the
[usage page](./usage.html).

If you still have questions about how to use the plugin, have a look at the
[GitHub Discussions](https://github.com/eclipse-csi/codesign-maven-plugin/discussions)
or open an [issue](https://github.com/eclipse-csi/codesign-maven-plugin/issues).
