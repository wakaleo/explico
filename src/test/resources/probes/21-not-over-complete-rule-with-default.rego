package probes.p21_not_over_complete_rule_with_default

import rego.v1

default checks_ok := false

checks_ok if {
	every check in input.pipeline.checks {
		check.status == "passed"
	}
}

deny if {
	not checks_ok
}
