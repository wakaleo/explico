package df2

import rego.v1

# METADATA
# scope: document
# title: Code shown as source
# description: Description of DF-2.
# custom:
#   control-id: DF-2
#   frameworks:
#     - ISO 27001 A.5.3
deny contains msg if {
	every x in [1, 2, 3] {
		x > 0
	}
	msg := "all positive"
}
