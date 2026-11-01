package sc4

import rego.v1

# METADATA
# scope: document
# title: Nothing ever matches
# description: Description of SC-4.
# custom:
#   control-id: SC-4
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.never == 1
	msg := "never one"
}

deny contains msg if {
	input.never == 2
	msg := "never two"
}
