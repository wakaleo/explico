package vsg

import rego.v1

# METADATA
# scope: document
# title: Split over two files, first
# description: Fixture control VSG-1.
# custom:
#   control-id: VSG-1
#   frameworks:
#     - ISO 27001 A.8.32
g_one if {
	input.kind == "a"
	json.marshal(input.a) == "1"
}
