package diff.conditionalvalue

import rego.v1

deny := "medium" if {
	input.risk > 80
}
