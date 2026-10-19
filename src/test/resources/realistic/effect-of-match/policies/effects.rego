package effects

import rego.v1

# METADATA
# scope: document
# title: Deny rule
# description: A rule named deny.
# custom:
#   control-id: EM-001
deny contains msg if {
	input.a == 1
	msg := "a is one"
}

# METADATA
# scope: document
# title: Violation rule
# description: A rule named violation.
# custom:
#   control-id: EM-002
violation contains msg if {
	input.b == 2
	msg := "b is two"
}

# METADATA
# scope: document
# title: Allow rule
# description: A rule named allow.
# custom:
#   control-id: EM-003
allow if {
	input.c == 3
}

# METADATA
# scope: document
# title: Flag rule
# description: A rule with a name that carries no convention.
# custom:
#   control-id: EM-004
flag if {
	input.d == 4
}

# METADATA
# scope: document
# title: Helper function
# description: A function, which has no effect of its own.
# custom:
#   control-id: EM-005
check(x) if {
	x == 5
}
