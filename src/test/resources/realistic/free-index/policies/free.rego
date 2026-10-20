package free.index

import rego.v1

# METADATA
# scope: document
# title: Undeclared index used once
# custom:
#   control-id: FI-001
wildcard_undeclared if {
	input.pods[i].privileged == true
}

# METADATA
# scope: document
# title: Declared index used once
# custom:
#   control-id: FI-002
wildcard_declared if {
	some i
	input.pods[i].privileged == true
}

# METADATA
# scope: document
# title: Index shared by two collections
# custom:
#   control-id: FI-003
join_index if {
	some i
	input.pods[i].name == input.names[i]
}

# METADATA
# scope: document
# title: Index also used as a value
# custom:
#   control-id: FI-004
value_use if {
	some i
	input.pods[i].privileged == true
	i > 0
}

# METADATA
# scope: document
# title: Free key then wildcard element
# custom:
#   control-id: FI-005
map_key if {
	container := input.review.object.spec[field][_]
	container.privileged == true
	field != "volumes"
}

# METADATA
# scope: document
# title: First use under a negation
# custom:
#   control-id: FI-006
negated_first if {
	not input.pods[i].ok
	input.allowed[i] == true
}

# METADATA
# scope: document
# title: Shared index inside a comprehension
# custom:
#   control-id: FI-007
comprehension_multi if {
	count({x | x := input.pods[i]; input.names[i] == x.name}) > 0
}

# METADATA
# scope: document
# title: Index shared with the rule head
# custom:
#   control-id: FI-008
head_use contains i if {
	input.pods[i].privileged == true
}

# METADATA
# scope: document
# title: Index under a function parameter
# custom:
#   control-id: FI-009
param_rooted(p) if {
	p.items[i].bad == true
	p.names[i] == "x"
}

# METADATA
# scope: document
# title: Wildcard straight under the root
# custom:
#   control-id: FI-010
root_wildcard if {
	input[_] == "forbidden"
}

# METADATA
# scope: document
# title: Shared index as an every domain
# custom:
#   control-id: FI-011
every_domain if {
	some i
	input.groups[i].enabled == true
	every m in input.members[i] {
		m.ok == true
	}
}
