package ex2

import rego.v1

# METADATA
# scope: document
# title: Matches tag y
# description: Description of EX-2.
# custom:
#   control-id: EX-2
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.tag == "y"
	msg := "y"
}
