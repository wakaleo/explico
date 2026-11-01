package sc8

import rego.v1

# METADATA
# scope: document
# title: An else chain
# description: Description of SC-8.
# custom:
#   control-id: SC-8
#   frameworks:
#     - ISO 27001 A.5.3
decision := "block" if {
	input.zone == "red"
} else := "warn" if {
	input.zone == "amber"
} else := "ok"
