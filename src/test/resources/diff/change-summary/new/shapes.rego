package cs.shapes

import rego.v1

# METADATA
# scope: document
# title: Gains a body
# description: Fixture rule CS-001.
# custom:
#   control-id: CS-001
deny contains msg if {
	input.a == 1
	msg := "a is one"
}

deny contains msg if {
	input.b == 2
	msg := "b is two"
}

# METADATA
# scope: document
# title: Message only
# description: Fixture rule CS-002.
# custom:
#   control-id: CS-002
violation contains msg if {
	input.x == 1
	msg := "new wording"
}

# METADATA
# scope: document
# title: Default outcome
# description: Fixture rule CS-003.
# custom:
#   control-id: CS-003
allow if {
	input.ok == true
}

default allow := true

# METADATA
# scope: document
# title: Every block only
# description: Fixture rule CS-004.
# custom:
#   control-id: CS-004
reject contains msg if {
	every item in input.items {
		item.ok == false
	}
	msg := "an item is not ok"
}

# METADATA
# scope: document
# title: Structure only
# description: Fixture rule CS-005.
# custom:
#   control-id: CS-005
flag contains msg if {
	input.p == 1
	input.r == 3
	msg := "first body"
}

flag contains msg if {
	input.q == 2
	msg := "second body"
}

# METADATA
# scope: document
# title: Frameworks only
# description: Fixture rule CS-006.
# custom:
#   control-id: CS-006
#   frameworks:
#     - SOC 2 CC8.1
#     - ISO 27001 A.8.32
block contains msg if {
	input.z == 9
	msg := "z is nine"
}
