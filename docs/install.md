# Install and first run

explico is a single jar. It needs a JDK and the `opa` binary, and nothing else.

## What you need

| Requirement | Detail |
|---|---|
| **Java** | JDK 17 or newer. |
| **`opa`** | Open Policy Agent **1.x** on your `PATH` (or point the `OPA_BIN` environment variable at the binary). explico is developed and tested against **1.19.0**; another 1.x should work but is not guaranteed byte for byte. `opa version` shows what you have. |
| **explico** | The jar `explico-<version>-all.jar` from the project's [GitHub Releases](https://github.com/wakaleo/explico/releases). To build it yourself: `./gradlew shadowJar`, which writes `build/libs/explico-<version>-all.jar`. |

Using explico from your own Kotlin or Java code instead is covered in [CLI and library](user-guide.md); the README's
[Install section](readme.md#install) has the Maven coordinates.

## Check it works

```sh
java -jar explico.jar version      # prints: explico 0.2.0
opa version                        # Version: 1.x.y
```

`version` reports only explico's own version; it does not check `opa`. The next command does.

## First run: the built-in demo

From an empty directory:

```sh
java -jar explico.jar demo
```

It extracts a small real policy set to `./explico-demo/`, renders it, and prints where to look:

```text
Rendered 6 documents to explico-demo/docs/ (100% coverage).
Open explico-demo/docs/release-approvals.md to see a rendered control card.
```

Open `explico-demo/docs/index.md` first: it lists every control, and each title links to its card. Running `demo` again in the same directory
refuses rather than overwriting; delete `explico-demo` first. No `opa` on your `PATH`? Add `--fetch-opa` and explico downloads a pinned,
checksum-verified build for the demo.

## Then your own policies

```sh
java -jar explico.jar render policies --out docs/policy-docs
```

Add `--examples examples` to see worked examples. The whole workflow, including keeping the documents up to date in CI, is
[From Rego to living documentation](guides/living-documentation.md).

## When something goes wrong

Every failure has a distinct exit code, and the message says what to do.

| Exit | What happened | What to do |
|---|---|---|
| `1` | Usage error: a bad flag, a missing required option (`--out`), a path that does not exist | Re-read the command; `java -jar explico.jar render --help` lists the options. |
| `2` | `opa` could not parse your Rego; its own message is shown unchanged, with the file and line | Fix the Rego. If the file uses the older `deny[msg] { ... }` syntax, add `--rego-version v0`. |
| `3` | `opa` is missing or is not version 1.x: *"explico requires opa version 1.x, but it could not be run …"* | Install `opa` 1.x and put it on `PATH`, or set `OPA_BIN`. |
| `4` | Two controls share a control id (`diff`), or two example files share a `name` (`render --examples`) | Make the ids or names unique. |
| `5` | A CI gate failed (`--fail-under`, `--fail-on-gaps`, `diff --fail-on`); the output **was still written** | Read the stderr line, which names the gate and why. See [CI recipes](guides/ci-recipes.md). |

A few things that look like problems and are not:

- **My example is missing from a card.** A card shows at most three matching and two non-matching examples, in file-name order. The index's
  *Example coverage* column counts them all. An example whose evaluation actually fails prints a warning on stderr naming it and is left out.
- **My rule says "matched", not "denied".** Only rules named exactly `deny`, `violation` or `allow` get the denied/allowed wording.
- **The coverage figure is below 100%.** Some construct is shown as source. See [Coverage](concepts/coverage.md).

Still stuck? The [Limits and FAQ](limits-and-faq.md) page covers the common questions, and the [glossary](glossary.md) defines the terms.
