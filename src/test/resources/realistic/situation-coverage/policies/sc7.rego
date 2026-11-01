package sc7

import rego.v1

# METADATA
# scope: document
# title: Three situations, two uncovered
# description: Description of SC-7.
# custom:
#   control-id: SC-7
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.t1 == true
	msg := "tier one"
}

deny contains msg if {
	input.t2 == true
	msg := "tier two"
}

deny contains msg if {
	input.t3 == true
	msg := "tier three"
}
