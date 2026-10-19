package quality.demo

import rego.v1

# METADATA
# scope: document
# title: Shared
# description: First control sharing a title.
# custom:
#   control-id: Q-001
#   frameworks:
#     - SOC 2 CC8.1
rule_one if {
	input.a == 1
}

# METADATA
# scope: document
# title: Shared
# description: Second control sharing a title, and naming no framework.
# custom:
#   control-id: Q-002
rule_two if {
	input.b == 2
}

# METADATA
# scope: document
# title: Undescribed
# custom:
#   control-id: Q-003
#   frameworks:
#     - ISO 27001 A.8.32
rule_three if {
	input.c == 3
}

# METADATA
# scope: document
# title: Fine
# description: Complete.
# custom:
#   control-id: Q-004
#   frameworks:
#     - SOC 2 CC8.1
rule_four if {
	input.d == 4
}

helper_without_metadata if {
	input.e == 5
}
