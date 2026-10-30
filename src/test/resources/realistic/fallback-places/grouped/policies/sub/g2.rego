package fpg

import rego.v1

# METADATA
# scope: document
# title: Split over two files, second
# description: Fixture control FPG-1.
# custom:
#   control-id: FPG-1
#   frameworks:
#     - ISO 27001 A.8.32
g_two if {
	input.kind == "b"
	helper_g with input as {"a": 1}
}

helper_g if {
	input.a == 1
}
