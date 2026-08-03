package probes.p05_unification_as_comparison

import rego.v1

deny if {
	input.change.author = input.change.approver
}
