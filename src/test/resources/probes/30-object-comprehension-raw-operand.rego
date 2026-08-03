package probes.p30_object_comprehension_raw_operand

import rego.v1

deny if {
	{s.name: s.status | some s in input.pipeline.stages} == {}
}
