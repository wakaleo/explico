package sc5

import rego.v1

# METADATA
# scope: document
# title: A boolean rule with two bodies
# description: Description of SC-5.
# custom:
#   control-id: SC-5
#   frameworks:
#     - ISO 27001 A.5.3
allow if {
	input.role == "admin"
}

allow if {
	input.role == "owner"
}
