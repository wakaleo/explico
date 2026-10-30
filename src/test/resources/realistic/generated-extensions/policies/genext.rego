package genext

import rego.v1

# METADATA
# title: At least two approvers
# custom:
#   control-id: GE-001
needs_two_approvers contains msg if {
	count(input.approvers) < 2
	msg := "fewer than two approvers"
}

# METADATA
# title: Not too many items, and a kind
# custom:
#   control-id: GE-002
bounded contains msg if {
	input.kind == "batch"
	count(input.items) >= 3
	msg := "a batch of three or more items"
}

# METADATA
# title: Signature predates the evaluation time
# custom:
#   control-id: GE-003
stale contains msg if {
	time.parse_rfc3339_ns(input.signed_at) < input.now
	msg := "signature is older than now"
}

# METADATA
# title: Not yet expired, operands the other way round
# custom:
#   control-id: GE-004
live contains msg if {
	input.now < time.parse_rfc3339_ns(input.expires_at)
	msg := "still valid"
}

# METADATA
# title: Requester is an approver
# custom:
#   control-id: GE-005
self_approved contains msg if {
	input.requester in input.approvers
	msg := "requester approved their own change"
}

# METADATA
# title: A count over reference data (cannot be solved from the input)
# custom:
#   control-id: GE-006
over_data contains msg if {
	count(data.limits.names) > 1
	msg := "too many names"
}

# METADATA
# title: Contradictory counts
# custom:
#   control-id: GE-007
contradiction contains msg if {
	count(input.xs) > 3
	count(input.xs) < 2
	msg := "cannot happen"
}
