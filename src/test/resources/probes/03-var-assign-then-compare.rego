package probes.p03_var_assign_then_compare

import rego.v1

deny if {
	env := input.deployment.environment
	env == "production"
}
