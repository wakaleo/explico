package tp

import rego.v1

deny contains msg if {
	input.a == 1
	msg := "bad"
}

used_only_by_a_test if {
	input.b == 1
}
