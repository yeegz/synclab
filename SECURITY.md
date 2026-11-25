# Security policy

SyncLab is a test-time library: it runs inside your test suite, opens no sockets, reads no network
data and writes only report files under `build/synclab-reports` (configurable).

Things we still treat as security issues:

- HTML reports that could execute script from trace content (the viewer builds its DOM with `textContent` only; tests check this).
- Report writing outside the configured directory (path traversal through test names).
- Dependency vulnerabilities in published artifacts.

## Reporting

Please report privately through GitHub's "Report a vulnerability" (Security Advisories) on the
repository, not in a public issue. You'll get an acknowledgement within 7 days. Fixes ship in a
patch release, with credit if you'd like it.

## Supported versions

The latest minor release gets fixes. Before 1.0, only the latest release is supported.
