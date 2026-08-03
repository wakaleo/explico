package probes.p08_multiple_independent_some_in

import rego.v1

deny if {
	some stage in input.pipeline.stages
	some check in input.pipeline.checks
	stage.status == "failed"
	check.status == "failed"
}
