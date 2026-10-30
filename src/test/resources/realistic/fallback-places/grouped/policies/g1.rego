package fpg

import rego.v1

# METADATA
# scope: document
# title: Split over two files, first
# description: Fixture control FPG-1.
# custom:
#   control-id: FPG-1
#   frameworks:
#     - ISO 27001 A.8.32
g_one if {
	input.kind == "a"
	unknown_check(input.a)
}
