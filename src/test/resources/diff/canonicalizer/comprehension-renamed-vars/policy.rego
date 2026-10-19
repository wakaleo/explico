package diff.sample

import rego.v1

deny contains msg if {
	input.deployment.environment == "production"
	count({approval | some approval in input.change.approvals; approval.role == "release-manager"}) == 0
	msg := "no release manager approval is recorded"
}
