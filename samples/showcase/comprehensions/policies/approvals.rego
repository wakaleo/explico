# METADATA
# title: Approval checks
# description: Rules that count or collect items with set, list and object comprehensions.
package approvals

import rego.v1

# METADATA
# title: A release manager must approve production changes
# custom:
#   control-id: APR-001
missing_manager_approval contains msg if {
	input.environment == "production"
	count({a | some a in input.approvals; a.role == "release-manager"}) == 0
	msg := "no release manager approval is recorded"
}

# METADATA
# title: No more than two failed checks
# custom:
#   control-id: APR-002
too_many_failures contains msg if {
	count([c | some c in input.checks; c.status == "failed"]) > 2
	msg := "more than two checks failed"
}

# METADATA
# title: Approvers are not the author
# custom:
#   control-id: APR-003
self_approved contains msg if {
	approvers := {a.user | some a in input.approvals}
	input.author in approvers
	msg := "the author approved their own change"
}
