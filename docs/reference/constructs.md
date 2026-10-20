# What explico renders

This is the reader's reference for what each piece of Rego becomes on a card. Every row below is **checked on every build**: a test
(`ConstructsReferenceTest`) wraps the Rego in a rule, renders it with the real pipeline, and fails if the text differs from the table. So
this page cannot drift from the tool. For the engineering history behind these decisions (what was audited, what was promoted and why),
see the [Rego coverage audit](../rego-coverage.md); for how the percentage is counted, see [Coverage](../concepts/coverage.md).

## How to read the tables

- Paths are shown as breadcrumbs with `▸`, and the leading `input` is dropped: `` `change ▸ author` `` is `input.change.author`.
- A row's Rego is the body of one rule. A `;` separates statements; the examples are otherwise independent of each other.
- `↳` marks a condition nested under the one above it (inside an `every`, say). Several bullets in one cell are consecutive bullets on the card.
- The examples assume these three helpers are defined in the same package:

```rego
package ref.doc

import rego.v1

exempt if input.exempt

max_items := 5

ok(x) if x.ok
```

## Rendered as text

Each of these becomes a readable bullet. Where a bullet names another rule or function, the card links it to that rule's own card.

### Comparisons

| Rego | Reads as |
|---|---|
| `input.env == "production"` | `` `env` is `"production"` `` |
| `input.env != "production"` | `` `env` is not `"production"` `` |
| `input.change.author == input.change.approver` | `` `change ▸ author` is `change ▸ approver` `` |
| `input.replicas >= 3` | `` `replicas` is at least `3` `` |
| `input.limits.cpu * 2 <= input.budget` | `` `limits ▸ cpu` times `2` is at most `budget` `` |
| `input.a == input.b + 1` | `` `a` is `b` plus `1` `` |
| `input.owner == null` | `` `owner` is `null` `` |

### Presence

| Rego | Reads as |
|---|---|
| `input.enabled` | `` `enabled` is present and not false `` |
| `not input.enabled` | `` `enabled` is absent or false `` |
| `not input.labels["a"]` | `` `labels ▸ "a"` is absent or false `` |
| `input.labels["signed-off-by"] != ""` | `` `labels ▸ "signed-off-by"` is not `""` `` |
| `input.items[0].ok` | `` `items ▸ 0 ▸ ok` is present and not false `` |

### Membership

| Rego | Reads as |
|---|---|
| `input.region in {"eu-west-1", "eu-west-2"}` | `` `region` is one of `"eu-west-1", "eu-west-2"` `` |
| `not input.region in {"us-east-1"}` | `` `region` is not one of `"us-east-1"` `` |

### Strings, patterns and types

| Rego | Reads as |
|---|---|
| `startswith(input.image, "registry.internal/")` | `` `image` starts with `"registry.internal/"` `` |
| `not endswith(input.name, "-test")` | `` `name` does not end with `"-test"` `` |
| `contains(input.url, "http:")` | `` `url` contains `"http:"` `` |
| `regex.match("^[a-z]+$", input.name)` | `` `name` matches pattern `"^[a-z]+$"` `` |
| `glob.match("*.example.com", [], input.host)` | `` `host` matches glob `"*.example.com"` `` |
| `lower(input.env) == "prod"` | `` `env` lowercased is `"prod"` `` |
| `input.name == lower(input.other)` | `` `name` is `other` lowercased `` |
| `concat(",", input.tags) == "a,b"` | `` `tags` joined with `","` is `"a,b"` `` |
| `is_string(input.id)` | `` `id` is a string `` |
| `net.cidr_contains("10.0.0.0/8", input.client.ip)` | `` `client ▸ ip` is within `"10.0.0.0/8"` `` |

### Counting and values

| Rego | Reads as |
|---|---|
| `count(input.approvals) >= 2` | `` the number of `approvals` is at least `2` `` |
| `count(input.approvals) == 0` | `` `approvals` is empty `` |
| `count(input.items) > max_items` | `` the number of `items` is greater than rule `max_items` `` |
| `sum(input.nums) > 10` | `` the sum of `nums` is greater than `10` `` |
| `object.get(input.limits, "cpu", 0) > 1` | `` `limits ▸ "cpu"` (default `0`) is greater than `1` `` |

### Iteration and quantifiers

| Rego | Reads as |
|---|---|
| `some p in input.pods; p.privileged == true` | `` for some p in `pods` ``<br>`` `pods ▸ [each p] ▸ privileged` is `true` `` |
| `input.pods[_].privileged == true` | `` any of: `pods ▸ privileged` is `true` `` |
| `some k, v in input.labels; v == ""` | `` for some k, v in `labels` ``<br>`` `labels ▸ [each v]` is `""` `` |
| `every c in input.containers { c.image != "" }` | `` for every c in `containers`, all of the following are true (vacuously true when there are none) ``<br>↳ `` `containers ▸ [each c] ▸ image` is not `""` `` |
| `some i; input.pods[i].name == input.names[i]` | `` for some index or key `i` of `pods` ``<br>`` `pods ▸ [each i] ▸ name` is `names ▸ [each i]` `` |
| `input.pods[i].a == 1; not input.names[i]` | `` for some index or key `i` of `pods` ``<br>`` `pods ▸ [each i] ▸ a` is `1` ``<br>`` `names ▸ [each i]` is absent or false `` |
| `count({p \| some p in input.pods; p.privileged}) == 0` | `` (the set of `pods ▸ [each p]` such that for some p in `pods`; `pods ▸ [each p] ▸ privileged` is present and not false) is empty `` |

### Other rules and functions

| Rego | Reads as |
|---|---|
| `not exempt` | `` rule `exempt` does not match `` |
| `ok(input.item)` | `` function `ok` is satisfied for `item` `` |
| `not ok(input.item)` | `` function `ok` is not satisfied for `item` `` |

### Local variables

| Rego | Reads as |
|---|---|
| `x := input.change.ticket; x.approved` | `` `change ▸ ticket ▸ approved` is present and not false `` |

### A condition rendered with one operand left as source

| Rego | Reads as |
|---|---|
| `json.marshal(input.x) != ""` | `` `json.marshal(input.x)` is not `""` `` |

## Shown as source

Anything else is shown as the **verbatim Rego** in a marked block, never described, and counted against the coverage figure. The reason is
a machine-readable tag, available in the [JSON output](../json-output.md).

| Rego | Why it is shown as source (reason) |
|---|---|
| `exempt with input.user as "admin"` | `with-override` |
| `net.cidr_is_valid(input.range)` | `function-call` |
| `y := json.marshal(input.x); y != ""` | `helper-assignment` |
| `count({r \| some r in input.regions; net.cidr_is_valid(r)}) > 3` | `comprehension` |
| `every c in input.containers { json.marshal(c) != "" }` | `every` |
| `every x in [1, 2] { x > 0 }` | `every` |

Two more cases are shown as source and are not rows above because they are not a single statement:

- an **`else` chain** (`else` branches are tried in order, which a list of situations would misstate), reason `else-chain`;
- a body that could not be mapped at all, reason `mapping-error`, with a warning on stderr. This should not happen on valid Rego; if it
  does, it is a bug worth reporting.

An `every` or a comprehension is rendered only when everything inside it is; one part explico cannot render keeps the whole block as source
(the last three rows above). See [True by construction](../concepts/true-by-construction.md).
