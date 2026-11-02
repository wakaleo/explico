# Install and first run

explico is a single jar. It needs a JDK and the `opa` binary, and nothing else.

## What you need

| Requirement | Detail |
|---|---|
| **Java** | JDK 17 or newer. |
| **`opa`** | Open Policy Agent **1.x** on your `PATH` (or point the `OPA_BIN` environment variable at the binary). explico is developed and tested against **1.19.0**; another 1.x should work but is not guaranteed byte for byte. `opa version` shows what you have. |
| **explico** | The jar `explico-<version>-all.jar` from the project's [GitHub Releases](https://github.com/wakaleo/explico/releases). To build it yourself: `./gradlew shadowJar`, which writes `build/libs/explico-<version>-all.jar`. |

Every command in these pages calls the jar `explico.jar`, so rename the download once (`mv explico-*-all.jar explico.jar`), or define
`alias explico='java -jar explico-<version>-all.jar'` and drop the `java -jar explico.jar` prefix. explico is tested on Linux, macOS and Windows.

Using explico from your own Kotlin or Java code instead is covered in [CLI and library](user-guide.md#adding-the-dependency) and [Use from Java](how-to/use-from-java.md), which have the Maven coordinates.

## Check it works

```sh
java -jar explico.jar version      # prints: explico 1.0.0
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

Every failure has a distinct exit code and a message saying what to do (`1` usage, `2` Rego parse error, `3` `opa` missing, `4` duplicates, `5` a CI gate).
[Troubleshooting](troubleshooting.md) maps each symptom to its cause and fix.

Next: the [Quickstart](quickstart.md) writes and renders a first rule in five minutes.

Still stuck? The [Limits and FAQ](limits-and-faq.md) page covers the common questions, and the [glossary](glossary.md) defines the terms.
