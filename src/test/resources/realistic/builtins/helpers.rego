package helpers

import rego.v1

deny contains msg if {
	a := json.marshal(input.xs)
	input.env == "prod"
	b := json.marshal(input.ys)
	json.is_valid(input.z)
	a > b
	msg := "split"
}

grouped contains msg if {
	x := json.marshal(input.p)
	y := json.marshal(input.q)
	z := json.marshal(input.r)
	x != y
	msg := "grouped"
}
