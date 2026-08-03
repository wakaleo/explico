package probes.p33_regex_match_real

import rego.v1

deny if {
	regex.match(`^rel-[0-9]+$`, input.deployment.id)
}
