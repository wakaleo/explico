package probes.p25_arithmetic_operand

import rego.v1

deny if {
	input.change.approvals_count + 1 > input.policy.minimum_approvals
}
