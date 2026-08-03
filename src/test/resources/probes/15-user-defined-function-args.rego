package probes.p15_user_defined_function_args

import rego.v1

is_production_like(env) if {
	env in {"production", "staging"}
}

deny if {
	is_production_like(input.deployment.environment)
	not input.change.ticket.approved
}
