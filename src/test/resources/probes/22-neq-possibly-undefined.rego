package probes.p22_neq_possibly_undefined

import rego.v1

deny if {
	input.change.author != input.change.approver
}
