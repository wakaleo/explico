package rc2

import rego.v1

# METADATA
# scope: document
# title: A control that is removed
# description: Description of RC-2.
# custom:
#   control-id: RC-2
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.level == 1
	msg := "denied"
}
