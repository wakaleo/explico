package diff.anothernewcontrol

import rego.v1

# METADATA
# scope: document
# title: Another brand new control
# custom:
#   control-id: AAA-001
deny contains msg if {
	input.w == 9
	msg := "w is 9"
}
