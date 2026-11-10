# Decision objects: ordered situations and a declared verdict

Two policies that return a decision object. An `else` chain is rendered as ordered situations (decided in order, first match wins), and `custom.verdict`, `custom.annotations` and `custom.timing` in METADATA tell explico which fields of the evaluated object are the verdict, the status and the timing, so the worked examples read as a verdict instead of a JSON blob.

[All examples](README.md)

## Rego policy

`actions.rego`

```rego
package actions

complete_form(subject, predicate, errors) = {
    "_type": "complete-form", "subject": subject,
    "predicate": predicate, "errors": errors,
}
```

`decisions.rego`

```rego
package shared

action_container(action) = {"action": action, "startBefore": null, "startAfter": null, "dueBy": null}

wrap_actions(actions) = [action_container(a) | some a in actions]

compliant(commentary, actions, notifications) = {
    "compliant": true, "commentary": commentary,
    "actions": wrap_actions(actions), "notifications": notifications,
}

non_compliant(commentary, actions, notifications) = {
    "compliant": false, "commentary": commentary,
    "actions": wrap_actions(actions), "notifications": notifications,
}

annotated(annotations, decision) = object.union(decision, {"annotations": annotations})

# Echo dueBy / startBefore onto the decision so a declared custom.timing
# projection (FR-5) can read them back from the evaluated object.
with_expiry_timing(decision, due_by, start_before) = object.union(decision, {
    "dueBy": due_by, "startBefore": start_before,
})

within_validity_period(attested_ns, days, now) if {
    now < attested_ns + (days * 24 * 60 * 60 * 1000000000)
}
```

`exception_waiver.rego`

```rego
# Note: `scope: document` METADATA sits directly above the rule it describes
# (see FR-6), never above `package` - opa rejects the latter.
package exception_waiver

import data.shared.compliant
import data.shared.non_compliant
import data.actions.complete_form

ns_per_day = 24 * 60 * 60 * 1000000000

has_request := count(input.waiverRequestHistory) > 0
current_request := input.waiverRequestHistory[0]
current_request_subject := concat(":", ["fact", current_request.id]) if has_request

first_request_ts_ns := min([time.parse_rfc3339_ns(r.timestamp) |
    some r in input.waiverRequestHistory]) if has_request

days_since_first_request := (input.now - first_request_ts_ns) / ns_per_day if has_request

requires_senior := days_since_first_request >= input.config.thresholds.requireSeniorAfterDays
requires_board := days_since_first_request >= input.config.thresholds.requireBoardAfterDays

current_self   := [s | some s in input.selfSignoffs;   s.subject == current_request_subject]
current_senior := [s | some s in input.seniorSignoffs; s.subject == current_request_subject]
current_board  := [s | some s in input.boardSignoffs;  s.subject == current_request_subject]

approved_self   := [s | some s in current_self;   s.value.decision == "approved"]
approved_senior := [s | some s in current_senior; s.value.decision == "approved"]
approved_board  := [s | some s in current_board;  s.value.decision == "approved"]

within_self_window if { days_since_first_request < input.config.thresholds.selfCoversDays }
within_senior_window if { days_since_first_request < input.config.thresholds.seniorCoversDays }

target_date_ns := time.parse_rfc3339_ns(concat("", [current_request.value.targetDate, "T00:00:00Z"])) if has_request

self_valid if { count(approved_self) > 0; within_self_window } else := false
senior_valid if { count(approved_senior) > 0; within_senior_window } else := false
board_valid if { count(approved_board) > 0; input.now < target_date_ns } else := false
self_and_senior_valid if { self_valid; senior_valid } else := false
all_signoffs_valid if { self_valid; senior_valid; board_valid } else := false

request_action := complete_form(input.subjectId, "waiver.request", [])
self_action    := complete_form(current_request_subject, "waiver.signoff.self", [])
senior_action  := complete_form(current_request_subject, "waiver.signoff.senior", [])
board_action   := complete_form(current_request_subject, "waiver.signoff.board", [])

self_action_list   := [self_action]   if within_self_window   else := []
senior_action_list := [senior_action] if within_senior_window else := []

# METADATA
# scope: document
# title: Exception waiver decision
# description: |
#   Decides whether a non-compliant subject is covered by a valid waiver. A
#   waiver must be requested, then signed off. The sign-off level required
#   escalates with how long the non-compliance has run: self-attestation first,
#   then a senior approver, then a governance board once it outlasts the
#   configured thresholds. Coverage from each level lasts only for its window.
# authors:
#   - Control Platform Team
# custom:
#   control-id: CTRL-WAIVER
#   frameworks:
#     - Internal Control Standard
#   verdict:
#     outcome: compliant
#     reason: commentary
#   actions:
#     at: actions
#     label: action.predicate
decision = non_compliant("No waiver requested", [request_action], []) if {
    not has_request
} else = non_compliant("Board sign-off required",
    array.concat(array.concat(self_action_list, senior_action_list), [board_action]), []) if {
    requires_board
    not all_signoffs_valid
} else = non_compliant("Senior sign-off required",
    array.concat(self_action_list, senior_action_list), []) if {
    requires_senior
    not self_and_senior_valid
} else = non_compliant("Self-attestation required", self_action_list, []) if {
    not self_valid
} else = compliant("Non-compliance waived", [], [])
```

`periodic_attestation.rego`

```rego
# Note: this METADATA sits on the final `decision` rule below (see FR-6), never
# above `package` - opa rejects a document-scope annotation on a package.
package periodic_attestation

import data.shared.annotated
import data.shared.compliant
import data.shared.non_compliant
import data.shared.within_validity_period
import data.shared.with_expiry_timing
import data.actions.complete_form

form_predicate := "periodic.attestation"
recertification_days := 180

applies  := input.form.value.applies
category := input.form.value.category

valid_submission if { applies == "no" }
valid_submission if { applies == "yes"; trim(category, " ") != "" }

attested_ns := time.parse_rfc3339_ns(input.form.timestamp) if input.form

_due_by := time.format(attested_ns + (recertification_days * 24 * 60 * 60 * 1000000000)) if attested_ns else := ""
_start_before := ""

base_decision := annotated(["not_started"],
    non_compliant("No attestation provided", [complete_form(input.subjectId, form_predicate, [])], [])) if {
    not input.form
} else := non_compliant("Attestation has expired", [complete_form(input.subjectId, form_predicate, [])], []) if {
    not within_validity_period(attested_ns, recertification_days, input.now)
} else := compliant("No regulated functions in use", [complete_form(input.subjectId, form_predicate, [])], []) if {
    applies == "no"
} else := compliant(sprintf("Regulated functions in use: %s", [category]), [complete_form(input.subjectId, form_predicate, [])], []) if {
    valid_submission
} else := non_compliant("Attestation is invalid", [complete_form(input.subjectId, form_predicate, [])], [])

# METADATA
# scope: document
# title: Periodic attestation
# description: |
#   Decides a periodic attestation for a subject. The control closes when a valid
#   attestation is submitted ("not applicable" or "applies, of a given category")
#   and must be recertified every 180 days; once it expires the control re-opens.
#   The final decision carries the verdict, the status annotation, and the timing,
#   so one declared projection reads all three from the evaluated object.
# authors:
#   - Control Platform Team
# custom:
#   control-id: CTRL-ATTEST
#   verdict:
#     outcome: compliant
#     reason: commentary
#   annotations:
#     at: annotations
#   timing:
#     dueBy: dueBy
#     startBefore: startBefore
decision := with_expiry_timing(base_decision, _due_by, _start_before)
```

## Rendered output

Generated by explico, shown unedited (inside a quote block so its headings stay separate from this page's).

> # Package `actions`
>
> *Source files: `shared/actions.rego`*
>
> <a id="actions-complete-form"></a>
>
> ## actions.complete_form
>
> *Rule `complete_form` in package `actions` — defined in `actions.rego`*
> *Parameters: `subject`, `predicate`, `errors`*
>
> *No description provided in policy metadata.*
>
> **Value:** {
>     "_type": "complete-form", "subject": subject,
>     "predicate": predicate, "errors": errors,
> }
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> *Package rendering coverage: 0 of 0 conditions (100%)*
>
> *Policy digest: `sha256:85d371848049…` (full value in index.md)*

> # Package `exception_waiver`
>
> *Source files: `waiver/exception_waiver.rego`*
>
> <a id="exception-waiver-all-signoffs-valid"></a>
>
> ## exception_waiver.all_signoffs_valid
>
> *Rule `all_signoffs_valid` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - see rule [`self_valid`](#exception-waiver-self-valid)
> - see rule [`senior_valid`](#exception-waiver-senior-valid)
> - see rule [`board_valid`](#exception-waiver-board-valid)
>
> *Then:* `true`
>
> ### Otherwise
>
> *Then:* `false`
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 3 of 3 conditions*
>
> ---
>
> <a id="exception-waiver-approved-board"></a>
>
> ## exception_waiver.approved_board
>
> *Rule `approved_board` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** [s | some s in current_board;  s.value.decision == "approved"]
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-approved-self"></a>
>
> ## exception_waiver.approved_self
>
> *Rule `approved_self` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** [s | some s in current_self;   s.value.decision == "approved"]
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"subject":"fact:req-1","value":{"decision":"approved"}}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-approved-senior"></a>
>
> ## exception_waiver.approved_senior
>
> *Rule `approved_senior` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** [s | some s in current_senior; s.value.decision == "approved"]
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-board-action"></a>
>
> ## exception_waiver.board_action
>
> *Rule `board_action` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** complete_form(current_request_subject, "waiver.signoff.board", [])
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.signoff.board","subject":"fact:req-1"}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-board-valid"></a>
>
> ## exception_waiver.board_valid
>
> *Rule `board_valid` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - rule [`approved_board`](#exception-waiver-approved-board) is not empty
> - `now` is less than rule [`target_date_ns`](#exception-waiver-target-date-ns)
>
> *Then:* `true`
>
> ### Otherwise
>
> *Then:* `false`
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
>   - `now`: `1736899200000000000`
> - **waiver covered by an approved self-attestation** — not matched
>   - `now`: `1736899200000000000`
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 2 of 2 conditions*
>
> ---
>
> <a id="exception-waiver-current-board"></a>
>
> ## exception_waiver.current_board
>
> *Rule `current_board` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** [s | some s in input.boardSignoffs;  s.subject == current_request_subject]
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-current-request"></a>
>
> ## exception_waiver.current_request
>
> *Rule `current_request` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** input.waiverRequestHistory[0]
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"id":"req-1","timestamp":"2025-01-10T00:00:00Z","value":{"targetDate":"2026-09-18"}}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-current-request-subject"></a>
>
> ## exception_waiver.current_request_subject
>
> *Rule `current_request_subject` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - see rule [`has_request`](#exception-waiver-has-request)
>
> **Value:** `"fact"`, `current_request.id` joined with `":"`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"fact:req-1"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="exception-waiver-current-self"></a>
>
> ## exception_waiver.current_self
>
> *Rule `current_self` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** [s | some s in input.selfSignoffs;   s.subject == current_request_subject]
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"subject":"fact:req-1","value":{"decision":"approved"}}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-current-senior"></a>
>
> ## exception_waiver.current_senior
>
> *Rule `current_senior` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** [s | some s in input.seniorSignoffs; s.subject == current_request_subject]
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-days-since-first-request"></a>
>
> ## exception_waiver.days_since_first_request
>
> *Rule `days_since_first_request` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - see rule [`has_request`](#exception-waiver-has-request)
>
> **Value:** `now` minus rule [`first_request_ts_ns`](#exception-waiver-first-request-ts-ns) divided by rule [`ns_per_day`](#exception-waiver-ns-per-day)
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"5"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="ctrl-waiver"></a>
>
> ## CTRL-WAIVER — Exception waiver decision
>
> *Rule `decision` in package `exception_waiver` — defined in `exception_waiver.rego`*
> *Frameworks: Internal Control Standard*
> *Authors: Control Platform Team*
>
> Decides whether a non-compliant subject is covered by a valid waiver. A
> waiver must be requested, then signed off. The sign-off level required
> escalates with how long the non-compliance has run: self-attestation first,
> then a senior approver, then a governance board once it outlasts the
> configured thresholds. Coverage from each level lasts only for its window.
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - rule [`has_request`](#exception-waiver-has-request) does not match
>
> *Then:* result of function [`non_compliant`](#shared-non-compliant) for `"No waiver requested"`, `[request_action]`, `[]`
>
> ### Situation 2 — no earlier situation applies and all of the following are true
>
> - see rule [`requires_board`](#exception-waiver-requires-board)
> - rule [`all_signoffs_valid`](#exception-waiver-all-signoffs-valid) does not match
>
> *Then:* result of function [`non_compliant`](#shared-non-compliant) for `"Board sign-off required"`, rule [`self_action_list`](#exception-waiver-self-action-list) followed by rule [`senior_action_list`](#exception-waiver-senior-action-list) followed by `[board_action]`, `[]`
>
> ### Situation 3 — no earlier situation applies and all of the following are true
>
> - see rule [`requires_senior`](#exception-waiver-requires-senior)
> - rule [`self_and_senior_valid`](#exception-waiver-self-and-senior-valid) does not match
>
> *Then:* result of function [`non_compliant`](#shared-non-compliant) for `"Senior sign-off required"`, rule [`self_action_list`](#exception-waiver-self-action-list) followed by rule [`senior_action_list`](#exception-waiver-senior-action-list), `[]`
>
> ### Situation 4 — no earlier situation applies and all of the following are true
>
> - rule [`self_valid`](#exception-waiver-self-valid) does not match
>
> *Then:* result of function [`non_compliant`](#shared-non-compliant) for `"Self-attestation required"`, rule [`self_action_list`](#exception-waiver-self-action-list), `[]`
>
> ### Otherwise
>
> *Then:* result of function [`compliant`](#shared-compliant) for `"Non-compliance waived"`, `[]`, `[]`
>
> **Worked examples**
>
> - **subject with no waiver requested** — ❌ not compliant
>   *"No waiver requested"*
>   *Remediation offered:*
>     - `waiver.request`
> - **waiver covered by an approved self-attestation** — ✅ compliant
>   *"Non-compliance waived"*
>   *No remediation offered.*
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 6 of 6 conditions*
>
> ---
>
> <a id="exception-waiver-first-request-ts-ns"></a>
>
> ## exception_waiver.first_request_ts_ns
>
> *Rule `first_request_ts_ns` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - see rule [`has_request`](#exception-waiver-has-request)
>
> **Value:** the smallest of (the list of the time `waiver request history ▸ [each r] ▸ timestamp` such that for some r in `waiver request history`)
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"1736467200000000000"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="exception-waiver-has-request"></a>
>
> ## exception_waiver.has_request
>
> *Rule `has_request` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** count(input.waiverRequestHistory) > 0
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-ns-per-day"></a>
>
> ## exception_waiver.ns_per_day
>
> *Rule `ns_per_day` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** 24 * 60 * 60 * 1000000000
>
> **Worked examples**
>
> - **subject with no waiver requested** — matched
>   *"86400000000000"*
> - **waiver covered by an approved self-attestation** — matched
>   *"86400000000000"*
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-request-action"></a>
>
> ## exception_waiver.request_action
>
> *Rule `request_action` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** complete_form(input.subjectId, "waiver.request", [])
>
> **Worked examples**
>
> - **subject with no waiver requested** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.request","subject":"SUBJ-12345"}"*
> - **waiver covered by an approved self-attestation** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.request","subject":"SUBJ-12345"}"*
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-requires-board"></a>
>
> ## exception_waiver.requires_board
>
> *Rule `requires_board` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** days_since_first_request >= input.config.thresholds.requireBoardAfterDays
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-requires-senior"></a>
>
> ## exception_waiver.requires_senior
>
> *Rule `requires_senior` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** days_since_first_request >= input.config.thresholds.requireSeniorAfterDays
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-self-action"></a>
>
> ## exception_waiver.self_action
>
> *Rule `self_action` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** complete_form(current_request_subject, "waiver.signoff.self", [])
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.signoff.self","subject":"fact:req-1"}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-self-action-list"></a>
>
> ## exception_waiver.self_action_list
>
> *Rule `self_action_list` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - see rule [`within_self_window`](#exception-waiver-within-self-window)
>
> *Then:* `[self_action]`
>
> ### Otherwise
>
> *Then:* `[]`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.signoff.self","subject":"fact:req-1"}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="exception-waiver-self-and-senior-valid"></a>
>
> ## exception_waiver.self_and_senior_valid
>
> *Rule `self_and_senior_valid` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - see rule [`self_valid`](#exception-waiver-self-valid)
> - see rule [`senior_valid`](#exception-waiver-senior-valid)
>
> *Then:* `true`
>
> ### Otherwise
>
> *Then:* `false`
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 2 of 2 conditions*
>
> ---
>
> <a id="exception-waiver-self-valid"></a>
>
> ## exception_waiver.self_valid
>
> *Rule `self_valid` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - rule [`approved_self`](#exception-waiver-approved-self) is not empty
> - see rule [`within_self_window`](#exception-waiver-within-self-window)
>
> *Then:* `true`
>
> ### Otherwise
>
> *Then:* `false`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 2 of 2 conditions*
>
> ---
>
> <a id="exception-waiver-senior-action"></a>
>
> ## exception_waiver.senior_action
>
> *Rule `senior_action` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** complete_form(current_request_subject, "waiver.signoff.senior", [])
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.signoff.senior","subject":"fact:req-1"}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="exception-waiver-senior-action-list"></a>
>
> ## exception_waiver.senior_action_list
>
> *Rule `senior_action_list` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - see rule [`within_senior_window`](#exception-waiver-within-senior-window)
>
> *Then:* `[senior_action]`
>
> ### Otherwise
>
> *Then:* `[]`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"{"_type":"complete-form","errors":[],"predicate":"waiver.signoff.senior","subject":"fact:req-1"}"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="exception-waiver-senior-valid"></a>
>
> ## exception_waiver.senior_valid
>
> *Rule `senior_valid` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - rule [`approved_senior`](#exception-waiver-approved-senior) is not empty
> - see rule [`within_senior_window`](#exception-waiver-within-senior-window)
>
> *Then:* `true`
>
> ### Otherwise
>
> *Then:* `false`
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 2 of 2 conditions*
>
> ---
>
> <a id="exception-waiver-target-date-ns"></a>
>
> ## exception_waiver.target_date_ns
>
> *Rule `target_date_ns` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - see rule [`has_request`](#exception-waiver-has-request)
>
> **Value:** the time `current_request.value.targetDate`, `"T00:00:00Z"` joined with `""`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   *"1789689600000000000"*
> - **subject with no waiver requested** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="exception-waiver-within-self-window"></a>
>
> ## exception_waiver.within_self_window
>
> *Rule `within_self_window` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - rule [`days_since_first_request`](#exception-waiver-days-since-first-request) is less than `config ▸ thresholds ▸ self covers days`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   - `config ▸ thresholds ▸ self covers days`: `90`
> - **subject with no waiver requested** — not matched
>   - `config ▸ thresholds ▸ self covers days`: `90`
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="exception-waiver-within-senior-window"></a>
>
> ## exception_waiver.within_senior_window
>
> *Rule `within_senior_window` in package `exception_waiver` — defined in `exception_waiver.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - rule [`days_since_first_request`](#exception-waiver-days-since-first-request) is less than `config ▸ thresholds ▸ senior covers days`
>
> **Worked examples**
>
> - **waiver covered by an approved self-attestation** — matched
>   - `config ▸ thresholds ▸ senior covers days`: `180`
> - **subject with no waiver requested** — not matched
>   - `config ▸ thresholds ▸ senior covers days`: `180`
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> *Package rendering coverage: 25 of 25 conditions (100%)*
>
> *Policy digest: `sha256:85d371848049…` (full value in index.md)*

> # Package `periodic_attestation`
>
> *Source files: `attestation/periodic_attestation.rego`*
>
> <a id="periodic-attestation-due-by"></a>
>
> ## periodic_attestation._due_by
>
> *Rule `_due_by` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - see rule [`attested_ns`](#periodic-attestation-attested-ns)
>
> *Then:* `time.format(attested_ns + (recertification_days * 24 * 60 * 60 * 1000000000))`
>
> ### Otherwise
>
> *Then:* `""`
>
> **Worked examples**
>
> - **subject with no waiver requested** — matched
>   *""*
> - **waiver covered by an approved self-attestation** — matched
>   *""*
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="periodic-attestation-start-before"></a>
>
> ## periodic_attestation._start_before
>
> *Rule `_start_before` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** ""
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="periodic-attestation-applies"></a>
>
> ## periodic_attestation.applies
>
> *Rule `applies` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** input.form.value.applies
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="periodic-attestation-attested-ns"></a>
>
> ## periodic_attestation.attested_ns
>
> *Rule `attested_ns` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - `form` is present and not false
>
> **Value:** the time `form ▸ timestamp`
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
>   - `form`: absent
> - **waiver covered by an approved self-attestation** — not matched
>   - `form`: absent
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="periodic-attestation-base-decision"></a>
>
> ## periodic_attestation.base_decision
>
> *Rule `base_decision` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> This rule is decided in order; the first matching situation wins.
>
> ### Situation 1 — all of the following are true
>
> - `form` is absent or false
>
> *Then:* result of function [`annotated`](#shared-annotated) for `["not_started"]`, result of function [`non_compliant`](#shared-non-compliant) for `"No attestation provided"`, `[complete_form(input.subjectId, form_predicate, [])]`, `[]`
>
> ### Situation 2 — no earlier situation applies and all of the following are true
>
> - function [`within_validity_period`](#shared-within-validity-period) is not satisfied for rule [`attested_ns`](#periodic-attestation-attested-ns), rule [`recertification_days`](#periodic-attestation-recertification-days), `now`
>
> *Then:* result of function [`non_compliant`](#shared-non-compliant) for `"Attestation has expired"`, `[complete_form(input.subjectId, form_predicate, [])]`, `[]`
>
> ### Situation 3 — no earlier situation applies and all of the following are true
>
> - rule [`applies`](#periodic-attestation-applies) is `"no"`
>
> *Then:* result of function [`compliant`](#shared-compliant) for `"No regulated functions in use"`, `[complete_form(input.subjectId, form_predicate, [])]`, `[]`
>
> ### Situation 4 — no earlier situation applies and all of the following are true
>
> - see rule [`valid_submission`](#periodic-attestation-valid-submission)
>
> *Then:* result of function [`compliant`](#shared-compliant) for `sprintf("Regulated functions in use: %s", [category])`, `[complete_form(input.subjectId, form_predicate, [])]`, `[]`
>
> ### Otherwise
>
> *Then:* result of function [`non_compliant`](#shared-non-compliant) for `"Attestation is invalid"`, `[complete_form(input.subjectId, form_predicate, [])]`, `[]`
>
> **Worked examples**
>
> - **subject with no waiver requested** — matched
>   *"{"actions":[{"action":{"_type":"complete-form","errors":[],"predicate":"periodic.attestation","subject":"SUBJ-12345"},"dueBy":null,"startAfter":null,"startBefore":null}],"annotations":["not_started"],"commentary":"No attestation provided","compliant":false,"notifications":[]}"*
>   - `form`: absent
>   - `now`: `1736899200000000000`
> - **waiver covered by an approved self-attestation** — matched
>   *"{"actions":[{"action":{"_type":"complete-form","errors":[],"predicate":"periodic.attestation","subject":"SUBJ-12345"},"dueBy":null,"startAfter":null,"startBefore":null}],"annotations":["not_started"],"commentary":"No attestation provided","compliant":false,"notifications":[]}"*
>   - `form`: absent
>   - `now`: `1736899200000000000`
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 4 of 4 conditions*
>
> ---
>
> <a id="periodic-attestation-category"></a>
>
> ## periodic_attestation.category
>
> *Rule `category` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** input.form.value.category
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="ctrl-attest"></a>
>
> ## CTRL-ATTEST — Periodic attestation
>
> *Rule `decision` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
> *Authors: Control Platform Team*
>
> Decides a periodic attestation for a subject. The control closes when a valid
> attestation is submitted ("not applicable" or "applies, of a given category")
> and must be recertified every 180 days; once it expires the control re-opens.
> The final decision carries the verdict, the status annotation, and the timing,
> so one declared projection reads all three from the evaluated object.
>
> **Value:** with_expiry_timing(base_decision, _due_by, _start_before)
>
> **Worked examples**
>
> - **subject with no waiver requested** — ❌ not compliant · *status: not_started*
>   *"No attestation provided"*
> - **waiver covered by an approved self-attestation** — ❌ not compliant · *status: not_started*
>   *"No attestation provided"*
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="periodic-attestation-form-predicate"></a>
>
> ## periodic_attestation.form_predicate
>
> *Rule `form_predicate` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** "periodic.attestation"
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="periodic-attestation-recertification-days"></a>
>
> ## periodic_attestation.recertification_days
>
> *Rule `recertification_days` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **Value:** 180
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="periodic-attestation-valid-submission"></a>
>
> ## periodic_attestation.valid_submission
>
> *Rule `valid_submission` in package `periodic_attestation` — defined in `periodic_attestation.rego`*
>
> *No description provided in policy metadata.*
>
> **The rule matches when ANY of the following situations applies:**
>
> ### Situation 1 — all of the following are true
>
> - rule [`applies`](#periodic-attestation-applies) is `"no"`
>
> ### Situation 2 — all of the following are true
>
> - rule [`applies`](#periodic-attestation-applies) is `"yes"`
> - rule [`category`](#periodic-attestation-category) trimmed of `" "` at both ends is not `""`
>
> **Worked examples**
>
> - **subject with no waiver requested** — not matched
> - **waiver covered by an approved self-attestation** — not matched
>
> *Examples are evaluated against this policy version by OPA at generation time.*
>
> *Rendering coverage: 3 of 3 conditions*
>
> ---
>
> *Package rendering coverage: 9 of 9 conditions (100%)*
>
> *Policy digest: `sha256:85d371848049…` (full value in index.md)*

> # Package `shared`
>
> *Source files: `shared/decisions.rego`*
>
> <a id="shared-action-container"></a>
>
> ## shared.action_container
>
> *Rule `action_container` in package `shared` — defined in `decisions.rego`*
> *Parameters: `action`*
>
> *No description provided in policy metadata.*
>
> **Value:** {"action": action, "startBefore": null, "startAfter": null, "dueBy": null}
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="shared-annotated"></a>
>
> ## shared.annotated
>
> *Rule `annotated` in package `shared` — defined in `decisions.rego`*
> *Parameters: `annotations`, `decision`*
>
> *No description provided in policy metadata.*
>
> **Value:** object.union(decision, {"annotations": annotations})
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="shared-compliant"></a>
>
> ## shared.compliant
>
> *Rule `compliant` in package `shared` — defined in `decisions.rego`*
> *Parameters: `commentary`, `actions`, `notifications`*
>
> *No description provided in policy metadata.*
>
> **Value:** {
>     "compliant": true, "commentary": commentary,
>     "actions": wrap_actions(actions), "notifications": notifications,
> }
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="shared-non-compliant"></a>
>
> ## shared.non_compliant
>
> *Rule `non_compliant` in package `shared` — defined in `decisions.rego`*
> *Parameters: `commentary`, `actions`, `notifications`*
>
> *No description provided in policy metadata.*
>
> **Value:** {
>     "compliant": false, "commentary": commentary,
>     "actions": wrap_actions(actions), "notifications": notifications,
> }
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="shared-with-expiry-timing"></a>
>
> ## shared.with_expiry_timing
>
> *Rule `with_expiry_timing` in package `shared` — defined in `decisions.rego`*
> *Parameters: `decision`, `due_by`, `start_before`*
>
> *No description provided in policy metadata.*
>
> **Value:** object.union(decision, {
>     "dueBy": due_by, "startBefore": start_before,
> })
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> <a id="shared-within-validity-period"></a>
>
> ## shared.within_validity_period
>
> *Rule `within_validity_period` in package `shared` — defined in `decisions.rego`*
> *Parameters: `attested_ns`, `days`, `now`*
>
> *No description provided in policy metadata.*
>
> **All of the following are true:**
>
> - parameter `now` is less than parameter `attested_ns` plus parameter `days` times `24` times `60` times `60` times `1000000000`
>
> *Rendering coverage: 1 of 1 conditions*
>
> ---
>
> <a id="shared-wrap-actions"></a>
>
> ## shared.wrap_actions
>
> *Rule `wrap_actions` in package `shared` — defined in `decisions.rego`*
> *Parameters: `actions`*
>
> *No description provided in policy metadata.*
>
> **Value:** [action_container(a) | some a in actions]
>
> *Rendering coverage: 0 of 0 conditions*
>
> ---
>
> *Package rendering coverage: 1 of 1 conditions (100%)*
>
> *Policy digest: `sha256:85d371848049…` (full value in index.md)*
