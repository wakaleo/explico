package ga

import rego.v1

# METADATA
# scope: document
# title: One control over two rules
# description: Description of G-1.
# custom:
#   control-id: G-1
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	input.ga == 1
	msg := "first"
}

deny contains msg if {
	input.ga == 2
	msg := "second"
}
