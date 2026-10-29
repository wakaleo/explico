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
