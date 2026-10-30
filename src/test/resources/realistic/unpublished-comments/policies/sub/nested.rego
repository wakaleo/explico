package ucsub

import rego.v1

# Notes for the maintainers,
# kept in a comment.
# METADATA
# scope: document
# title: Nested file
# description: Fixture control UC-5.
# custom:
#   control-id: UC-5
#   frameworks:
#     - ISO 27001 A.8.32
uc_five if {
	input.kind == "e"
}
