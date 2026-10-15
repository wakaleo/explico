package authz

import rego.v1

default allow := true

# METADATA
# title: Admins may do anything
# custom:
#   control-id: AZ-001
allow if {
	"admin" in input.user.roles
}

# METADATA
# title: Owners can edit own docs
# custom:
#   control-id: AZ-002
allow if {
	input.resource.owner == input.user.id
}
