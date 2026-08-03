package probes.p09_nested_every

import rego.v1

deny if {
	every stage in input.pipeline.stages {
		every check in stage.checks {
			check.status == "passed"
		}
	}
}
