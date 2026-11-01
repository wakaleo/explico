package sc2

import rego.v1

# METADATA
# scope: document
# title: Two situations, both covered
# description: Description of SC-2.
# custom:
#   control-id: SC-2
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.level == "x"
	msg := "level x"
}

deny contains msg if {
	input.level == "y"
	msg := "level y"
}
