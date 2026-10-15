package diff.expressionassign

import rego.v1

deny contains msg if {
	d := input.a - input.c
	d > 5
	msg := "too far"
}
