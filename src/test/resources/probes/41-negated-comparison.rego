package probes.p41_negated_comparison

import rego.v1

deny if {
	not input.change.author == input.change.approver
}
