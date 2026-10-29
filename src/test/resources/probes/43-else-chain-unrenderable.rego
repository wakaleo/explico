package probes.p43_else_chain_unrenderable

import rego.v1

verdict := "blocked" if {
	input.deployment.environment == "production"
} else := "review" if {
	input.deployment.size > 3 with input.deployment.size as 5
} else := "allowed"
