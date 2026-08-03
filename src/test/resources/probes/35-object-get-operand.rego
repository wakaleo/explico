package probes.p35_object_get_operand

import rego.v1

deny if {
	object.get(input.change, "ticket", "none") == "none"
}
