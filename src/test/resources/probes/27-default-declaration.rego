package probes.p27_default_declaration

import rego.v1

default allow := false

allow if {
	input.deployment.environment == "production"
	input.change.ticket.approved
}
