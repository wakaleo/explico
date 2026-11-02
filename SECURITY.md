# Security policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately, not in a public issue: use GitHub's **Security → Report a
vulnerability** on this repository. Include the explico version, how you ran it, and a minimal policy or input that
reproduces the problem. You will get an acknowledgement, and a fix or an explanation, as soon as practical.

## Supported versions

Only the latest release receives fixes (0.x: the API may still change). Releases are signed with the key published on
`keyserver.ubuntu.com`; the signing key's fingerprint is printed in each release workflow run.

## What explico runs, and what that means for you

- **Rendering without worked examples only parses.** `explico render <dir>` without `--examples` runs `opa parse` and
  `opa inspect`, which read the Rego but do not evaluate it. Rendering untrusted policies this way is safe.
- **Worked examples execute the policy.** With `--examples`, explico runs `opa eval` against each fixture, which
  *evaluates the policy*. Rego has builtins that reach outside the process (for example `http.send`,
  `net.lookup_ip_addr`, and `opa.runtime`, which exposes environment variables). **Do not render worked examples for a
  policy you do not trust**, and run example generation in an environment without credentials or network access you are
  unwilling to expose. The same applies to the `Explico.render(..., examples = ...)` library call.
- **`--data` on its own also runs `opa eval`, for the Reference-data section on a card** -- but each `data.*` path is
  checked first and only queried when it names no loaded rule or package (or an ancestor of one), so this call never
  evaluates Rego rule logic and cannot reach a builtin. The supplied data is still copied into the rendered Markdown
  verbatim, same as any other value shown on a card.
- **Masking (`custom.mask`) is a documentation aid, not a security boundary.** It hides values that are in the fixture input from the rendered Markdown and
  JSON. It does not stop `opa` from reading and evaluating the real fixture (the fixture is written to a temporary file for the duration of the run), it does
  not hide a value the rule derives or reads from `data`, a fixed string in a rule or its METADATA, or what `opa` prints to stderr when an example fails
  (explico passes that through, and a "Skipping fixture" warning names the fixture). A malformed `custom.mask` stops the render rather than being ignored,
  and a path that hides nothing in any example is warned about, but neither can know what a rule echoes. Review the output of a new rule before publishing it.
- **Text you supply is written into the output.** The `--companion-url` and `--source-url` templates and the `--companion-label` are the operator's; file and
  package names are percent-encoded into the URLs and the label is limited to plain text, so a policy author cannot break out of a link. Control ids, titles
  and descriptions come from METADATA and are shown as written: explico escapes `|` and line breaks so text cannot break a table, but it does **not** sanitise Markdown or HTML, so the
  generated documents are as trustworthy as the policies they came from. If you publish them through a site generator that allows raw HTML, review the policy authors' METADATA first.
- **`--out` is replaced, but only where it is safe to.** `render --out <dir>` deletes the directory and writes it again (so output never goes stale). It does that only
  to a directory that is missing, empty, or marked `.explico-output` (written into every output directory); any other non-empty directory is refused, and nothing is
  deleted, unless you pass `--force`. Nothing overrides these refusals, even `--force`: `--out` may not be, or contain, the policy, example, data or requirements paths, the working
  directory, your home directory or a filesystem root. Paths are compared by real path, so `..` and symlinks do not get round them.
- **explico drives an external `opa` binary.** It uses the `opa` on `PATH` or the one named by `OPA_BIN`. Install `opa`
  from a source you trust. `explico demo --fetch-opa` downloads `opa` from the official GitHub release and verifies its
  SHA-256 checksum against the release's own checksum file.
- **No network access of its own** apart from `explico demo --fetch-opa`.

## Dependencies

Runtime dependencies are limited to kotlinx.serialization, Clikt (and its transitive libraries). The resolved dependency
graphs are scanned against the OSV vulnerability database before each release (see `CHANGELOG.md` and `CLAUDE.md`).
