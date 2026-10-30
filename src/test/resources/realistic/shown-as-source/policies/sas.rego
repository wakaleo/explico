package sas

import rego.v1

# METADATA
# scope: document
# title: Every one
# description: Fixture control SA-1.
# custom:
#   control-id: SA-1
#   frameworks:
#     - ISO 27001 A.8.32
every_one if {
	every x in input.items {
		json.marshal(x) == "a"
	}
}

# METADATA
# scope: document
# title: Every two
# description: Fixture control SA-2.
# custom:
#   control-id: SA-2
#   frameworks:
#     - ISO 27001 A.8.32
every_two if {
	input.kind == "b"
	every y in input.others {
		unknown_check(y)
	}
}

# METADATA
# scope: document
# title: Else chain
# description: Fixture control SA-3.
# custom:
#   control-id: SA-3
#   frameworks:
#     - ISO 27001 A.8.32
else_chain := "a" if {
	input.k == 1
} else := "b" if {
	unknown_check(input.k)
}

# METADATA
# scope: document
# title: With override
# description: Fixture control SA-4.
# custom:
#   control-id: SA-4
#   frameworks:
#     - ISO 27001 A.8.32
with_override if {
	helper_ok with input as {"a": 1}
}

# METADATA
# scope: document
# title: Unknown function
# description: Fixture control SA-5.
# custom:
#   control-id: SA-5
#   frameworks:
#     - ISO 27001 A.8.32
unknown_function if {
	unknown_check(input.x)
}

# METADATA
# scope: document
# title: Helper assignment
# description: Fixture control SA-6.
# custom:
#   control-id: SA-6
#   frameworks:
#     - ISO 27001 A.8.32
helper_assignment if {
	encoded := json.marshal(input.a)
	encoded == "1"
}

# METADATA
# scope: document
# title: Partial rule reference
# description: Fixture control SA-7.
# custom:
#   control-id: SA-7
#   frameworks:
#     - ISO 27001 A.8.32
partial_reference if {
	partial_set
}

# METADATA
# scope: document
# title: Unclassified construct
# description: Fixture control SA-8.
# custom:
#   control-id: SA-8
#   frameworks:
#     - ISO 27001 A.8.32
unclassified_one if {
	input.kind == "a"
	[input.a]
}

# METADATA
# scope: document
# title: Fully rendered
# description: Fixture control SA-9.
# custom:
#   control-id: SA-9
#   frameworks:
#     - ISO 27001 A.8.32
fully_rendered if {
	input.kind == "a"
}

helper_ok if {
	input.a == 1
}

helper_fallback if {
	unknown_check(input.y)
}

partial_set contains m if {
	m := input.kind
	input.kind == "a"
}
