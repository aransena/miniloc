# miniloc Universal Contracts

This directory declares the Universal Contracts surface for miniloc.

Selected UC profiles:

- `universal.profile.cli_tool_package.v1` for the host helper scripts and
  Gradle/deploy commands.
- `universal.profile.edge_telemetry_module.v1` for the phone-side local
  telemetry sensor surface, with a project-local limitation: miniloc is a
  single-phone, local-LAN sensor and does not implement fleet management, OTA,
  cloud sync, or stored recording bundles.

Conformance target:

- `shape`: JSON descriptors parse and contain required portable fields.
- `portable`: paths, API endpoints, command surfaces, permissions,
  environment requirements, and manual test evidence are discoverable.
- `profile`: partial for edge telemetry because miniloc exposes live HTTP
  responses rather than persisted stream files.

The root descriptor is `miniloc.manifest.json`.
