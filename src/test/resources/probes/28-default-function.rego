package probes.p28_default_function

import rego.v1

default risk_score(_) := 0

risk_score(finding) := 10 if {
	finding.severity == "critical"
}

deny if {
	risk_score(input.finding) > 5
}
