package fs

import rego.v1

# This block explains the policy
# but never reaches the documentation.

# METADATA
# scope: document
# title: No description
# custom:
#   control-id: FS-1
#   frameworks:
#     - ISO 27001 A.8.32
no_description if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: No framework
# description: Fixture control FS-2.
# custom:
#   control-id: FS-2
no_framework if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Never matched
# description: Fixture control FS-3.
# custom:
#   control-id: FS-3
#   frameworks:
#     - ISO 27001 A.8.32
never_matched if {
	input.kind == "never"
}

# METADATA
# scope: document
# title: Raw object
# description: Fixture control FS-4.
# custom:
#   control-id: FS-4
#   frameworks:
#     - ISO 27001 A.8.32
raw_object := {"compliant": input.ok, "commentary": "checked"} if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Shown as source
# description: Fixture control FS-5.
# custom:
#   control-id: FS-5
#   frameworks:
#     - ISO 27001 A.8.32
shown_as_source if {
	input.kind == "a"
	helper_ok with input as {"a": 1}
}

# METADATA
# scope: document
# title: Shared title
# description: Fixture control FS-6.
# custom:
#   control-id: FS-6
#   frameworks:
#     - ISO 27001 A.8.32
shared_one if {
	input.kind == "a"
}

# METADATA
# scope: document
# title: Shared title
# description: Fixture control FS-7.
# custom:
#   control-id: FS-7
#   frameworks:
#     - ISO 27001 A.8.32
shared_two if {
	input.kind == "a"
}

helper_ok if {
	input.a == 1
}
