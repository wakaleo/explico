package probes.p38_truthy_non_boolean_field

import rego.v1

deny if {
	input.change.author
}
