package qhg

import rego.v1

# METADATA
# scope: document
# title: Split decision, first half
# description: Fixture control QH-G.
# custom:
#   control-id: QH-G
#   frameworks:
#     - ISO 27001 A.8.32
first_half := {"compliant": input.ok, "commentary": "one"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Split decision, second half
# description: Fixture control QH-G.
# custom:
#   control-id: QH-G
#   frameworks:
#     - ISO 27001 A.8.32
second_half := {"compliant": input.ok, "commentary": "two"} if {
	input.kind == "a"
}
