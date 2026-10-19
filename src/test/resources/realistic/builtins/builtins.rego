package builtins

import rego.v1

deny contains msg if {
	net.cidr_contains("10.0.0.0/8", input.client.ip)
	is_string(input.user.id)
	not is_number(input.user.age)
	is_boolean(input.flags.beta)
	not is_null(input.user.deleted_at)
	is_array(input.items)
	is_object(input.meta)
	is_set(input.tags)
	msg := "condition builtins"
}

deny contains msg if {
	sum(input.amounts) > 1000
	max(input.scores) >= 90
	min(input.scores) < 10
	to_number(input.count_text) == 5
	trim_space(input.name) == "root"
	count(object.keys(input.labels)) > 3
	time.parse_rfc3339_ns(input.created_at) < time.now_ns()
	msg := "operand builtins"
}

deny contains msg if {
	json.is_valid(input.payload)
	is_string({x | some x in input.xs; net.cidr_is_valid(x)})
	msg := "unsupported or unmappable"
}
