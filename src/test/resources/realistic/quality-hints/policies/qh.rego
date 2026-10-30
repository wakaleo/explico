package qh

import rego.v1

# METADATA
# scope: document
# title: Kind a is denied
# description: Fixture control QH-1.
# custom:
#   control-id: QH-1
#   frameworks:
#     - ISO 27001 A.8.32
deny contains msg if {
	input.kind == "a"
	msg := "kind a"
}

# METADATA
# scope: document
# title: Never exercised
# description: Fixture control QH-2.
# custom:
#   control-id: QH-2
#   frameworks:
#     - ISO 27001 A.8.32
never_denied contains msg if {
	input.kind == "never"
	msg := "never"
}

# METADATA
# scope: document
# title: Decision as an object
# description: Fixture control QH-3.
# custom:
#   control-id: QH-3
#   frameworks:
#     - ISO 27001 A.8.32
decision := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Declared decision object
# description: Fixture control QH-4.
# custom:
#   control-id: QH-4
#   frameworks:
#     - ISO 27001 A.8.32
#   verdict:
#     outcome: compliant
#     reason: commentary
verdict_decision := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Boolean rule
# description: Fixture control QH-5.
# custom:
#   control-id: QH-5
#   frameworks:
#     - ISO 27001 A.8.32
allowed if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: A function
# description: Fixture control QH-6.
# custom:
#   control-id: QH-6
#   frameworks:
#     - ISO 27001 A.8.32
is_kind_a(k) if {
	k == "a"
}

# METADATA
# scope: document
# title: Partial object rule
# description: Fixture control QH-7.
# custom:
#   control-id: QH-7
#   frameworks:
#     - ISO 27001 A.8.32
flags[name] := true if {
	name := input.kind
	name == "a"
}
