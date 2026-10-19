# METADATA
# title: API authorization
# description: Who may call which endpoint.
package api.authz

import rego.v1

# METADATA
# title: Admins may do anything
# custom:
#   control-id: AUTHZ-001
admin_access if {
	"admin" in input.user.roles
}

# METADATA
# title: Owners may read their own records
# custom:
#   control-id: AUTHZ-002
owner_read if {
	input.method == "GET"
	input.resource.owner == input.user.id
}

# METADATA
# title: Elevated access needs an active grant
# description: Writes are allowed only while the user holds an active grant.
# custom:
#   control-id: AUTHZ-003
granted_write if {
	input.method in {"PUT", "POST", "DELETE"}
	data.grants[input.user.id].active == true
}

# METADATA
# scope: document
# title: Request is allowed
# description: The overall decision, built from the three rules above.
# custom:
#   control-id: AUTHZ-004
allow if admin_access

allow if owner_read

allow if granted_write
