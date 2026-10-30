package nc

import rego.v1

import data.nc2.shared_rule

# METADATA
# scope: document
# title: Uses every helper shape
# description: Fixture control NC-1.
# custom:
#   control-id: NC-1
#   frameworks:
#     - ISO 27001 A.8.32
nc_one if {
	helper_plain
	count(input.items) < max_items
	every x in input.xs {
		x.ok
		every_helper
	}
	count([y | some y in input.ys; comp_helper]) > 0
	shared_rule
}

# METADATA
# scope: document
# title: Else branch
# description: Fixture control NC-2.
# custom:
#   control-id: NC-2
#   frameworks:
#     - ISO 27001 A.8.32
nc_two := "a" if {
	input.k == 1
} else := "b" if {
	else_helper
}

# METADATA
# scope: document
# title: With override
# description: Fixture control NC-3.
# custom:
#   control-id: NC-3
#   frameworks:
#     - ISO 27001 A.8.32
nc_three if {
	helper_with with input as {"a": 1}
}

helper_plain if {
	input.a == 1
}

every_helper if {
	input.b == 1
}

comp_helper if {
	input.c == 1
}

else_helper if {
	input.d == 1
}

helper_with if {
	input.a == 1
}

max_items := 5

unused_const := 3

unused_fn(x) if {
	x == 1
}

default flag_only := false

default entry_point := false

entry_point if {
	input.kind == "z"
}

deny if {
	input.kind == "b"
}

allow_other if {
	input.kind == "c"
}
