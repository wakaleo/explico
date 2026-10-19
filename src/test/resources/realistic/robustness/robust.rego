package robust

import rego.v1

# METADATA
# title: Object-shaped frameworks
# description: custom.frameworks declared as an object, as real-world rule sets do.
# custom:
#   control-id: RB-001
#   frameworks:
#     cis-aws-1.4:
#       - "1.20"
deny contains msg if {
	input.kind == "bucket"
	msg := "bucket flagged"
}

lookup(x) := {"name": x}

call_headed contains msg if {
	lookup(input.x).name == "a"
	msg := "call-headed chain"
}

pair_ok([a, b]) if a == b

pattern_param contains msg if {
	pair_ok(input.pair)
	msg := "pattern parameter"
}

misaligned contains msg if {
	input.ok
	msg := sprintf("%d of %v", [input.total, input.name])
}

aligned contains msg if {
	input.ok
	msg := sprintf("%v of %v", [input.total, input.name])
}
