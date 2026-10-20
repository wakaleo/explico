package parampaths

import rego.v1

risk_score(finding) := 10 if {
	finding.severity == "critical"
}

risk_score(finding) := 5 if {
	finding.severity == "high"
	finding.owner.team == "platform"
}

has_label(res) if {
	res.labels["env"] == "prod"
}

bad(res) if {
	res.items[idx].ok == res.flags[idx]
}

in_set(x) if {
	x in {"a", "b"}
}

deny contains msg if {
	risk_score(input.finding) > 5
	msg := "x"
}
