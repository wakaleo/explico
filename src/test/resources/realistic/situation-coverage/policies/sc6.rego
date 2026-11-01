package sc6

import rego.v1

# METADATA
# scope: document
# title: A message that is not a template
# description: Description of SC-6.
# custom:
#   control-id: SC-6
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.mode == "p"
	msg := "mode p"
}

deny contains msg if {
	input.mode == "q"
	msg := input.note
}
