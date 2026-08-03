package probes.p36_lower_upper_operand

import rego.v1

deny if {
	lower(input.change.author) == "asmith"
}

deny if {
	upper(input.deployment.environment) == "PRODUCTION"
}
