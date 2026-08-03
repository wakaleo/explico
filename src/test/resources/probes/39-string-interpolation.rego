package probes.p39_string_interpolation

import rego.v1

deny if {
	x := input.deployment.id
	msg := $"Deployment {x} was rejected"
}
