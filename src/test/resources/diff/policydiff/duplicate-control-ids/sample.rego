package diff.duplicate

import rego.v1

# METADATA
# scope: document
# title: First rule
# custom:
#   control-id: DUP-001
deny contains msg if {
	input.a == 1
	msg := "a is 1"
}

# METADATA
# scope: document
# title: Second rule
# custom:
#   control-id: DUP-001
violation contains msg if {
	input.b == 2
	msg := "b is 2"
}
