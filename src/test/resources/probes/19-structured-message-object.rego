package probes.p19_structured_message_object

import rego.v1

deny contains msg if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
	msg := {"code": "NO_TICKET", "detail": "no approved change ticket"}
}
