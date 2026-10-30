# Licence-style header, three lines:
# who owns this file,
# and why it exists.
package uc

import rego.v1

# METADATA
# scope: document
# title: Body comments
# description: Fixture control UC-1.
# custom:
#   control-id: UC-1
#   frameworks:
#     - ISO 27001 A.8.32
uc_one if {
	input.kind == "a"
	# indented comment inside the body
# column-one comment inside the body
	input.ok == true
}

# A detached note of two lines
# between two rules.

# METADATA
# scope: document
# title: After the detached note
# description: Fixture control UC-2.
# custom:
#   control-id: UC-2
#   frameworks:
#     - ISO 27001 A.8.32
uc_two if {
	input.kind == "b" # trailing remark
}

# one single-line comment, not reported

# Plain explanation, line one.
# Plain explanation, line two.
# Plain explanation, line three.
# METADATA
# scope: document
# title: Plain lines then METADATA
# description: Fixture control UC-3.
# custom:
#   control-id: UC-3
#   frameworks:
#     - ISO 27001 A.8.32
uc_three if {
	input.kind == "c"
}
