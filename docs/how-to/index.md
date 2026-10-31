# How-to guides

Short, task-shaped pages. Each assumes you can already run `explico render` (if not, start with the [Quickstart](../quickstart.md)) and says
exactly which flag or METADATA key does the job. Every command and output shown was produced by a real run.

| I want to… | Guide |
|---|---|
| Document a policy library I did not write, with no METADATA and old Rego syntax | [Document existing policies](document-existing-policies.md) |
| Add worked examples, or have explico propose some | [Add worked examples](add-worked-examples.md) |
| Link every card to its Rego line at a given commit | [Link cards to source](link-cards-to-source.md) |
| Keep names, e-mail addresses or ids from fixtures out of the documents | [Keep sensitive values out of examples](mask-sensitive-values.md) |
| Show which compliance requirements have a control, and fail when one has none | [Evidence compliance requirements](evidence-requirements.md) |
| Show a reviewer what a policy change does | [Review a policy change](review-a-policy-change.md) |
| Call explico from Java or Kotlin | [Use explico from Java](use-from-java.md) |
| Fix an error or a surprising result | [Troubleshooting](../troubleshooting.md) |

Bigger workflows live elsewhere: [From Rego to living documentation](../guides/living-documentation.md) (repository layout through publishing) and
[CI recipes](../guides/ci-recipes.md) (GitHub Actions, GitLab, gates). Every flag is in the [CLI and library reference](../user-guide.md).
