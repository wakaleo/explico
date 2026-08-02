package diff.newcontrol

import rego.v1

# METADATA
# scope: document
# title: Brand new control
# custom:
#   control-id: NEW-001
deny contains msg if {
	input.x == 1
	msg := "x is 1"
}
