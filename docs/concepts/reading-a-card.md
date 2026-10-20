# Reading a card

Every rule with a title, a control id, or anything to show gets a **card**: a section of a package page. This is a real card, from
the sample policies (`explico demo` produces it):

```markdown
## REL-001 — Production change approval

*Rule `deny` in package `release.approvals` — defined in `change_approval.rego`*
*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*

Production deployments must reference an approved change ticket,
and the change author must not approve their own change.

**Effect of a match:** ❌ denied *(by the `deny` naming convention)*

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ ticket ▸ approved` is absent or false

*Produces:* "release [deployment id] has no approved change ticket"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ author` is `change ▸ approver`

*Produces:* "change [change id] was approved by its author"
```

## The parts

| Part | Where it comes from |
|---|---|
| Heading (`REL-001 — Production change approval`) | METADATA `custom.control-id` and `title`. With no control id, the heading is the package and rule name. |
| *Rule … defined in …* | The rule's name, package and file. With [`--source-url`](../user-guide.md), the file name links to the exact line in your repository. |
| *Frameworks* | METADATA `custom.frameworks`. |
| Description | METADATA `description`, shown as written. |
| **Effect of a match** | Only for rules named `deny`, `violation` or `allow`, and it says it rests on that naming convention. No line for any other name. |
| **Situations** | One per body of the rule. A rule with several bodies matches when **any** of them does; the conditions inside one body must **all** hold. |
| Conditions | One bullet per condition, from a fixed phrase for each kind of Rego expression (see below). |
| *Produces* | The message the body returns, with `sprintf` placeholders shown as `[deployment id]`. Present only when every placeholder is understood. |

## Reading a condition

Paths are shown as breadcrumbs with `▸`, with the leading `input` dropped: `` `change ▸ ticket ▸ approved` `` is `input.change.ticket.approved`.

| You see | It means |
|---|---|
| `` `x` is `"a"` `` / `` `x` is not `"a"` `` | an equality / inequality |
| `` `x` is absent or false `` | `not input.x` — the field is missing, or false |
| `` `x` is present and not false `` | a bare `input.x` |
| `` `x` is one of `"a", "b"` `` | membership in a list |
| `any of: …` | the path contains a wildcard (`[_]`): the condition holds for **at least one** element |
| `[each x]` in a path | the element currently being looked at (from `some x in …` or an index variable) |
| for some `x` in `coll` | `some x in coll` — the following bullets are about one such `x` |
| for every `x` in `coll`, all of the following are true (vacuously true when there are none) | `every x in coll { … }`; true for an empty collection |
| function `f` is satisfied for … | a call to a user-defined function, linked to its own card |
| rule `r` / `` `r` `` linked | a reference to another rule, linked to its card |

## Worked examples

When you supply fixtures (`--examples`), a card gains a **Worked examples** section: each fixture with its verdict, the message (and
which situation produced it) and the values the card's conditions read from that input. `absent` means the input has no value at that
path. The footer states that the examples were evaluated by OPA at generation time.

`--generate-examples` adds a separate **Generated examples** section for gaps your fixtures leave; every candidate is verified by
`opa eval` before it is shown, and the names are mechanical ("satisfies every condition", "breaks only: …"), never invented.

## The footer

`*Rendering coverage: 4 of 4 conditions*` — how many of this card's conditions were rendered rather than shown as source. See
[Coverage](coverage.md).
