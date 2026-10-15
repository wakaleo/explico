package exprs

import rego.v1

required := {"a", "b"}

deny contains msg if {
	provided := {x | some x in input.provided}
	missing := required - provided
	count(missing) > 0
	msg := "missing"
}

warn contains msg if {
	total := count(input.items) + 1
	total > 10
	msg := "many"
}

over if {
	diff := input.a - input.b
	diff > 5
}

twice if {
	n := count(input.xs)
	n > 1
	n < 10
}

guard if {
	x := json.marshal(input.a)
	x == "1"
}

chained if {
	a := input.p
	b := a + 1
	b > 2
}
