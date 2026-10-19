package meta.ownership

import rego.v1

# METADATA
# scope: document
# title: Example with full METADATA
# description: Exercises authors, related_resources, and extra custom fields.
# authors:
# - Ann Owner <ann@example.com>
# - name: Bob Reviewer
# related_resources:
# - ref: https://example.com/runbook
#   description: Runbook
# - https://example.com/ticket/123
# custom:
#   control-id: META-001
#   frameworks:
#     - SOC 2 CC8.1
#   owner: platform-team
#   tags:
#     - tier1
#     - production
#   excluded:
#     reason: not applicable
deny contains msg if {
	input.x == 1
	msg := "x is one"
}

# METADATA
# scope: document
# title: Plain rule, no extras
# description: No authors, no related_resources, no custom fields beyond the usual two.
# custom:
#   control-id: META-002
allow if {
	input.y == 1
}
