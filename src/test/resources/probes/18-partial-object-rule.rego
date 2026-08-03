package probes.p18_partial_object_rule

import rego.v1

deny_severity[msg] := "high" if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
	msg := "no approved change ticket"
}
