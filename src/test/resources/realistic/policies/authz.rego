package authz

import rego.v1

default allow := false

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
	input.action == "edit"
	input.resource.owner == input.user.id
	not input.resource.locked
}

allow if {
	input.action == "read"
	some grant in data.grants[input.user.id]
	grant.resource == input.resource.id
	glob.match("read*", [], grant.permission)
}

allow if {
	input.method == "GET"
	input.path[0] == "public"
}

allow if {
	count(input.user.roles) > 0
	input.user.mfa
	time.now_ns() < input.session.expires_ns
}

risk_level := "high" if {
	input.amount > 10000
} else := "medium" if {
	input.amount > 1000
} else := "low"

allow if risk_level == "low"

allow if {
	net.cidr_contains("10.0.0.0/8", input.client.ip)
	is_string(input.user.id)
	input.user.id != ""
}

allow if {
	every role in input.user.roles {
		role != "banned"
	}
}
