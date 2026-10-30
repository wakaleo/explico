package vt

import rego.v1

# METADATA
# scope: document
# title: A raw string argument
# description: Fixture control VT-1.
# custom:
#   control-id: VT-1
#   frameworks:
#     - ISO 27001 A.8.32
vt_one if {
	input.kind == "a"
	json.marshal(`a`) == "1"
}
