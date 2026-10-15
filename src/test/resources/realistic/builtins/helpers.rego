package helpers

import rego.v1

deny contains msg if {
	a := count(input.xs)
	input.env == "prod"
	b := count(input.ys)
	json.is_valid(input.z)
	a > b
	msg := "split"
}
