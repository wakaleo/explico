package fpsub

import rego.v1

# METADATA
# scope: document
# title: In a subdirectory
# description: Fixture control FP-5.
# custom:
#   control-id: FP-5
#   frameworks:
#     - ISO 27001 A.8.32
fp_five if {
	unknown_check(input.s)
}
