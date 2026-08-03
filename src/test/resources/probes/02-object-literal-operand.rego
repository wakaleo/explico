package probes.p02_object_literal_operand

import rego.v1

deny if {
	input.change == {"author": "asmith", "approved": true}
}
