package fsp

import rego.v1

# First block, line one
# First block, line two

# METADATA
# scope: document
# title: Alpha
# custom:
#   control-id: P-1
#   frameworks:
#     - ISO 27001 A.8.32
p_one if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Alpha
# custom:
#   control-id: P-2
#   frameworks:
#     - ISO 27001 A.8.32
p_two if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Beta
# description: Fixture control P-3.
# custom:
#   control-id: P-3
p_three if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Beta
# description: Fixture control P-4.
# custom:
#   control-id: P-4
p_four if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Never one
# description: Fixture control P-5.
# custom:
#   control-id: P-5
#   frameworks:
#     - ISO 27001 A.8.32
p_five if {
	input.kind == "never"
}

# Second block, line one
# Second block, line two

# METADATA
# scope: document
# title: Never two
# description: Fixture control P-6.
# custom:
#   control-id: P-6
#   frameworks:
#     - ISO 27001 A.8.32
p_six if {
	input.kind == "never"
}

# METADATA
# scope: document
# title: Object one
# description: Fixture control P-7.
# custom:
#   control-id: P-7
#   frameworks:
#     - ISO 27001 A.8.32
p_seven := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Object two
# description: Fixture control P-8.
# custom:
#   control-id: P-8
#   frameworks:
#     - ISO 27001 A.8.32
p_eight := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: With one
# description: Fixture control P-9.
# custom:
#   control-id: P-9
#   frameworks:
#     - ISO 27001 A.8.32
p_nine if {
	input.kind == "a"
	helper_ok with input as {"a": 1}
}

# METADATA
# scope: document
# title: With two
# description: Fixture control P-10.
# custom:
#   control-id: P-10
#   frameworks:
#     - ISO 27001 A.8.32
p_ten if {
	input.kind == "a"
	helper_ok with input as {"a": 1}
}

helper_ok if {
	input.a == 1
}
