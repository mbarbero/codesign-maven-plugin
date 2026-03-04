# TODO

- [x] prevent a release of the project from happenign if vulnerabilies are detected from its SBOM
- [x] remove dangerous ways to pass token (e.g., cli arg, mayeb others?). If config file is kept, add what is needed to check permissions when running on windows (currently only posix platform is managed).
  - [x] use jimfs <https://google.github.io/jimfs/> to test config file rather than having tests annotated with @EnabledOnOs
  - [x] make the path to the config file containing the token configurable (cli and maven plugin), and make the default `~/.config/eclipse-csi-codesign/config.properties`
- [x] update general documentation as well compliance documentation from the changes that happens since last time those were updated.
- [x] remove the csi.codesign.apiToken parameter from maven

- [ ] update security.md with recommendation from BSI-TR-03183-3 (<https://www.bsi.bund.de/SharedDocs/Downloads/EN/BSI/Publications/TechGuidelines/TR03183/BSI-TR-03183-3_v1_0_0.pdf?__blob=publicationFile&v=3>). Do
- [ ] assess the generated sbom against recommandation from BSI-TR-03183-2
- [ ] update general documentation as well compliance documentation from the changes that happens since last time those were updated.

- [ ] update binary name from codesign to csi-codesign

- [ ] generate changelog for initial version from the **full** git commit log
