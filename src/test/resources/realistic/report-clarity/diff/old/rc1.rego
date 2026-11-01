package rc1

import rego.v1

# METADATA
# scope: document
# title: A control that calls a helper
# description: Description of RC-1.
# custom:
#   control-id: RC-1
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.amount > 100
	not is_exempt
	msg := "denied"
}

is_exempt if {
	input.exempt == true
}
