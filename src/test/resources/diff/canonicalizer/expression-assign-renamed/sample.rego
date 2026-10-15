package diff.expressionassign

import rego.v1

deny contains msg if {
	gap := input.a - input.b
	gap > 5
	msg := "too far"
}
