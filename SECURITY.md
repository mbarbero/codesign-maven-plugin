# Security Policy

This Eclipse Foundation Project adheres to the [Eclipse Foundation Vulnerability Reporting Policy](https://www.eclipse.org/security/policy/).

## How To Report a Vulnerability

**Please do not report security vulnerabilities through public issues, discussions, or change requests.**

Report it using one of the following channels:

* Report a [vulnerability](https://github.com/eclipse-csi/codesign-maven-plugin/security/advisories/new) via private vulnerability reporting on GitHub
* Contact the [Eclipse Foundation Security Team](mailto:security@eclipse-foundation.org) via email
* Create a [confidential issue](https://gitlab.eclipse.org/security/vulnerability-reports/-/issues/new?issuable_template=new_vulnerability) in the Eclipse Foundation Vulnerability Reporting Tracker

You can find more information about reporting and disclosure at the [Eclipse Foundation Security page](https://www.eclipse.org/security/).

Where possible, please encrypt sensitive vulnerability reports using the Eclipse Foundation Security Team's PGP key (available at the [Eclipse Foundation Security page](https://www.eclipse.org/security/)) and send via signed email.

Anonymous reports are accepted. Note that anonymous reports can only be processed to a limited extent if technical or content-related queries cannot be answered.

## What To Include in Your Report

Please provide as much of the following information as possible:

* The type of issue (e.g. authentication bypass, injection, information disclosure)
* Affected version(s)
* Severity and potential impact, including how an attacker might exploit the issue
* Step-by-step instructions to reproduce the issue
* Location of the affected source code (tag, branch, commit, or direct URL)
* Full paths of source files related to the manifestation of the issue
* Configuration required to reproduce the issue
* Proof-of-concept or exploit code (if available)
* Log files related to the issue (if available)
* At least one valid contact option (email preferred) so we can ask follow-up questions

This information will help us triage your report more quickly.

## Scope

A report is considered a valid vulnerability if:

* It affects this project's software or its published release artefacts.
* It relates to publicly unknown information.
* It is not solely a result of automated scanning tools without supporting documentation.

## Out of Scope

The following are generally **not** considered valid vulnerabilities:

* Theoretical attacks without a working proof of concept
* Vulnerabilities in third-party dependencies that are not exploitable through this project
* Issues already publicly known or already reported

## Response Timelines

| Milestone | Target |
| --- | --- |
| Acknowledgement (non-automated) | Within 5 working days of receipt |
| Detailed feedback (confirmation, rejection, or explanation of delay) | Within 10 working days of receipt |
| Public disclosure of validated and verified vulnerabilities | Within 90 days; may be extended once by a further 90 days with justification |

If we need more time to investigate, we will explain why and commit to a follow-up update within the next 10 working days.

## Our Commitments to Reporters

* All incoming reports are treated confidentially to the extent permitted by law.
* Personal data of the reporting entity will not be disclosed to third parties without explicit consent.
* We will not pursue criminal charges against reporters who act in good faith and comply with this policy. This does not apply where recognisable criminal intentions are apparent.
* We will not require reporters to sign a non-disclosure agreement (NDA).
* We will remain available as a contact throughout the entire CVD process.
* If requested, and after the CVD process is complete, we will acknowledge the reporter's name or alias (as provided) in our release notes or security advisory.

## Reporter Code of Conduct

To qualify for acknowledgement, reporters are expected to:

* Not abuse the reported vulnerability beyond what is necessary to demonstrate it.
* Not conduct attacks (social engineering, DoS, brute force, etc.) against project infrastructure.
* Not manipulate, compromise, or modify systems or data belonging to third parties.
* Not offer exploit tools to third parties.

Reports from entities that do not comply with the above will still be processed to the best extent possible, but acknowledgement may be withheld.

## Vulnerability Disclosure

Validated and verified vulnerabilities will be publicly disclosed within **90 days** of the report. In exceptional cases, with valid justification, this period may be extended once by a further 90 days.

Public disclosure takes the form of a GitHub Security Advisory for this repository. Advisories include:

* A description of the vulnerability and affected versions
* The severity (CVSS base score where applicable)
* Remediation or mitigation guidance
* Credit to the reporter (if requested and consented to)

The CVD process is considered complete when:

* The vulnerability report is assessed as unfounded, or
* The vulnerability has been fixed (or mitigated) and publicly disclosed, or
* The reporter has not responded to follow-up queries for at least 30 days and the report cannot be processed further.

## Supported Versions

Only the latest released version is supported. Vulnerability fixes will not be backported to earlier versions.
