# Relationship to OPA and Rego

explico is a documentation and change-reporting layer *on top of* OPA, not a replacement for any part of it, and not affiliated with or
endorsed by the Open Policy Agent project or Styra.

- **It drives the real `opa` binary rather than reimplementing Rego.** Every parse, metadata lookup and evaluation goes through
  `opa parse`, `opa inspect` and `opa eval` as subprocess calls (see [Architecture](../../ARCHITECTURE.md) for why). explico never contains its
  own Rego parser or evaluator, so its understanding of a policy cannot silently drift from what OPA itself would do with it.
- **It reads standard OPA METADATA annotations only**, the same `# METADATA` YAML-block convention
  [documented by OPA itself](https://www.openpolicyagent.org/docs/latest/annotations/), not an explico-specific markup dialect. Any
  already-annotated, OPA-compliant policy set can be rendered as-is; nothing needs adding purely to make explico happy.
- **It targets OPA 1.x**, developed and tested against 1.19.0 specifically (CI pins that version; the frozen goldens were validated against it, so
  another 1.x *should* work but is not guaranteed byte for byte). `opa parse`'s JSON shape and the METADATA format are specified per major version by
  OPA, so a 2.x, if one ever exists, is not assumed compatible without re-verifying.
- **Rego v0 is supported, explicitly.** This is about the Rego *syntax* of your policies, which is separate from the version of the `opa` binary.
  Policies in the legacy v0 syntax (`deny[msg] { ... }`, as in the Gatekeeper library) render with `--rego-version v0` (library: `RegoVersion.V0`).
  The default is v1, and the version is always chosen by you and never auto-detected.

## Compared with other Rego tooling

- [Regal](https://www.openpolicyagent.org/ecosystem/entry/regal), Styra's Rego linter and language server, checks whether your Rego is
  *well written*: style, bugs, best practices. explico does not lint anything; it assumes the policy is already valid and renders what it
  *means*.
- [Konstraint](https://github.com/plexsystems/konstraint) is the closest existing tool in spirit: it also generates a Markdown document from
  METADATA annotations. Its `doc` subcommand is a byproduct of its primary job (converting Rego into Kubernetes Gatekeeper
  ConstraintTemplates), scoped to policies that use Gatekeeper's `violation[]` convention. explico is a dedicated, general-purpose documentation and diff
  tool for any Rego policy set, with worked-example evaluation and a structural change report that Konstraint's generator does not have.

explico documents and diffs policies; it is not a linter and not a replacement for OPA.
