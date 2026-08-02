package diff.unchanged

import rego.v1

# METADATA
# scope: document
# title: Stable control
# custom:
#   control-id: UNCH-001
deny contains msg if {
	input.z == 3
	msg := "z is 3"
}
