package sc1

import rego.v1

# METADATA
# scope: document
# title: Three situations, one uncovered
# description: Description of SC-1.
# custom:
#   control-id: SC-1
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.kind == "a"
	msg := "kind a"
}

deny contains msg if {
	input.kind == "b"
	msg := "kind b"
}

deny contains msg if {
	input.kind == "c"
	msg := "kind c"
}
