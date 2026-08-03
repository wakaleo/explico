package probes.p34_time_now_ns_operand

import rego.v1

deny if {
	time.now_ns() > input.deployment.timestamp
}
