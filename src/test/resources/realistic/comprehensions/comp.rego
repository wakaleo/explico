package comp

import rego.v1

# METADATA
# custom:
#   control-id: CP-001
set_empty contains msg if {
	count({a | some a in input.approvals; a.role == "manager"}) == 0
	msg := "no manager approval"
}

# METADATA
# custom:
#   control-id: CP-002
array_count contains msg if {
	count([x | some x in input.items; x.enabled == true]) > 2
	msg := "too many enabled"
}

# METADATA
# custom:
#   control-id: CP-003
bound_then_used contains msg if {
	names := {n | some item in input.items; n := item.name}
	"root" in names
	msg := "root present"
}

# METADATA
# custom:
#   control-id: CP-004
object_comp contains msg if {
	count({k: v | some k, v in input.labels; v == "x"}) > 0
	msg := "labelled"
}

# METADATA
# custom:
#   control-id: CP-005
outer_variable contains msg if {
	some team in input.teams
	count({m | some m in input.members; m.team == team.name}) > 5
	msg := "big team"
}

# METADATA
# custom:
#   control-id: CP-006
unrenderable_body contains msg if {
	count({a | some a in input.xs; net.cidr_is_valid(a)}) > 0
	msg := "has valid cidr"
}

big(items) if count(items) > 2

# METADATA
# custom:
#   control-id: CP-007
function_argument contains msg if {
	big({x | some x in input.xs; x.on == true})
	msg := "big"
}

# METADATA
# custom:
#   control-id: CP-008
key_variable contains msg if {
	some k, v in input.labels
	startswith(k, "team-")
	v == "x"
	msg := "team label"
}
