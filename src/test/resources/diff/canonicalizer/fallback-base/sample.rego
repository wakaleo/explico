package diff.sample

import rego.v1

deny contains msg if {
	input.deployment.environment == "production"
	count({a | some a in input.change.approvals; a.role == "release-manager"}) == 0
	msg := "no release manager approval is recorded"
}
