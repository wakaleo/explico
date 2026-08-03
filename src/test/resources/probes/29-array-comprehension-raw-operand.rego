package probes.p29_array_comprehension_raw_operand

import rego.v1

deny if {
	[s.name | some s in input.pipeline.stages; s.status == "failed"] == ["build"]
}
