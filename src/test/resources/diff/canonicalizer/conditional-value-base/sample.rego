package diff.conditionalvalue

import rego.v1

deny := "high" if {
	input.risk > 80
}
