package unif

import rego.v1

# METADATA
# custom:
#   control-id: UB-001
substituted contains msg if {
	env = input.deployment.environment
	env == "production"
	msg := "production"
}

# METADATA
# custom:
#   control-id: UB-002
wildcard contains msg if {
	c = input.containers[_]
	c.privileged == true
	msg := "privileged"
}

# METADATA
# custom:
#   control-id: UB-003
reversed contains msg if {
	input.containers[_] = c
	c.image == "busybox"
	msg := "busybox"
}

# METADATA
# custom:
#   control-id: UB-004
message_by_unification contains msg if {
	input.encrypted == false
	msg = sprintf("bucket %v is not encrypted", [input.name])
}

# METADATA
# custom:
#   control-id: UB-005
already_bound_stays_a_comparison contains msg if {
	y := input.wanted
	y = input.items[_]
	msg := "wanted item present"
}

# METADATA
# custom:
#   control-id: UB-006
unrenderable_rhs_stays_a_fallback contains msg if {
	x = json.marshal(input.payload)
	x == "{}"
	msg := "empty payload"
}

# METADATA
# custom:
#   control-id: UB-007
inside_a_comprehension contains msg if {
	count({p | p = input.perms[_]; p > 600}) > 0
	msg := "loose permission"
}
