package nc2

import rego.v1

shared_rule if {
	input.a == 1
}

deny if {
	input.kind == "b"
}
