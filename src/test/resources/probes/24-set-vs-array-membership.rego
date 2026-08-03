package probes.p24_set_vs_array_membership

import rego.v1

deny if {
	input.deployment.environment in {"production", "staging"}
}

deny if {
	input.change.approver in input.change.previous_approvers
}
