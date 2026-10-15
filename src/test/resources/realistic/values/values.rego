package values

import rego.v1

tier := "high" if input.risk > 80

tier := "low" if input.risk <= 80

flag if input.enabled

limit := n if {
	n := input.limit
}

label := sprintf("%v-x", [input.name]) if input.named

single := "only" if input.solo

explicit := true if input.on
