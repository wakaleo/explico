package ex1

import rego.v1

# METADATA
# scope: document
# title: Matches tag x
# description: Description of EX-1.
# custom:
#   control-id: EX-1
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.tag == "x"
	msg := "x"
}
