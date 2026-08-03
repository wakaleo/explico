package probes.p31_composite_value_membership

import rego.v1

deny if {
	pairs := {[1, 2], [3, 4]}
	[1, 2] in pairs
}
