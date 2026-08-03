package probes.p14_var_in_rule_head_ref

import rego.v1

users_by_role[role][id] := user if {
	some id, user in input.users
	role := user.role
}

deny if {
	users_by_role.admin.u1.name == "asmith"
}
