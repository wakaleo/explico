package probes.p17_else_chain

import rego.v1

verdict := "blocked" if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
} else := "allowed" if {
	true
}

deny if {
	verdict == "blocked"
}
