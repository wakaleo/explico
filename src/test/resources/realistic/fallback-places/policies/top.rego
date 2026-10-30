package fp

import rego.v1

# METADATA
# scope: document
# title: Two calls
# description: Fixture control FP-1.
# custom:
#   control-id: FP-1
#   frameworks:
#     - ISO 27001 A.8.32
fp_one if {
	input.kind == "a"
	unknown_check(input.x)
	input.ok == true
	unknown_check(input.y)
}

# METADATA
# scope: document
# title: An every block
# description: Fixture control FP-2.
# custom:
#   control-id: FP-2
#   frameworks:
#     - ISO 27001 A.8.32
fp_two if {
	input.kind == "b"
	every x in input.items {
		x.ok
		unknown_check(x)
	}
}

# METADATA
# scope: document
# title: A with override
# description: Fixture control FP-3.
# custom:
#   control-id: FP-3
#   frameworks:
#     - ISO 27001 A.8.32
fp_three if {
	helper_ok with input as {"a": 1}
}

# METADATA
# scope: document
# title: An else chain
# description: Fixture control FP-4.
# custom:
#   control-id: FP-4
#   frameworks:
#     - ISO 27001 A.8.32
fp_four := "a" if {
	input.k == 1
} else := "b" if {
	unknown_check(input.k)
}

helper_ok if {
	input.a == 1
}

helper_fallback if {
	input.z == 1
	unknown_check(input.y)
}
