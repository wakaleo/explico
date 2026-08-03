package probes.p01_null_literal

import rego.v1

deny if {
	input.change.author == null
}
