package ucplain

import rego.v1

# METADATA
# scope: document
# title: Only METADATA
# description: Fixture control UC-4.
# custom:
#   control-id: UC-4
#   frameworks:
#     - ISO 27001 A.8.32
uc_four if {
	input.kind == "d" # trailing
}
