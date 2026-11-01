package sc3

import rego.v1

# METADATA
# scope: document
# title: One situation
# description: Description of SC-3.
# custom:
#   control-id: SC-3
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.flag == true
	msg := "flag set"
}
