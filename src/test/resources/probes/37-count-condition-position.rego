package probes.p37_count_condition_position

import rego.v1

deny if {
	count(input.change.approvals)
}
