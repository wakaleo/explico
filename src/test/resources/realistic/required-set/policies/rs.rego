package rs

import rego.v1

# METADATA
# scope: document
# title: Complete control
# description: Fixture control.
# custom:
#   control-id: RS-1
#   frameworks:
#     - ISO 27001 A.8.32
#   verdict:
#     outcome: compliant
#     reason: commentary
complete_ok := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# description: Fixture control.
# custom:
#   control-id: RS-2
#   frameworks:
#     - ISO 27001 A.8.32
untitled if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Undescribed
# custom:
#   control-id: RS-3
#   frameworks:
#     - ISO 27001 A.8.32
undescribed if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Frameworkless
# description: Fixture control.
# custom:
#   control-id: RS-4
frameworkless if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Never exercised
# description: Fixture control.
# custom:
#   control-id: RS-5
#   frameworks:
#     - ISO 27001 A.8.32
never_exercised if {
	input.kind == "never"
}

# METADATA
# scope: document
# title: Raw object
# description: Fixture control.
# custom:
#   control-id: RS-6
#   frameworks:
#     - ISO 27001 A.8.32
raw_object := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Shared title
# description: Fixture control.
# custom:
#   control-id: RS-7
#   frameworks:
#     - ISO 27001 A.8.32
shared_one if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Shared title
# description: Fixture control.
# custom:
#   control-id: RS-8
#   frameworks:
#     - ISO 27001 A.8.32
shared_two if {
	input.kind == "a"
}
