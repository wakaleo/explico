package probes.p10_with_override

import rego.v1

deny if {
	is_release_candidate with input.deployment.environment as "production"
}

is_release_candidate if {
	input.deployment.environment == "production"
}
