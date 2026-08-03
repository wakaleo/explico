package probes.p26_string_concat_operand

import rego.v1

deny if {
	concat("/", [input.change.namespace, input.change.name]) == input.change.full_name
}
