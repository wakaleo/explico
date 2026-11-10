package attestation_check

import data.checks.fix_standard
import data.checks.is_failing
import data.checks.reason_for
import data.shared.compliant
import data.shared.non_compliant

code := "STANDARD5"

# METADATA
# title: STANDARD5 - Logical flows have a current attestation
# custom:
#   verdict: { outcome: compliant, reason: commentary }
#   actions: { at: actions, label: action.standardId }
decision = non_compliant(
    reason_for(input.report.standards, code, "Reported as failing"),
    [fix_standard(input.subject, code)],
    [],
) if {
    is_failing(input.report.standards, code)
} else = compliant(reason_for(input.report.standards, code, "No failure reported"), [], [])
