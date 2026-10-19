package diff.sample

import rego.v1

deny contains msg if {
	input.deployment.environment == "production"
	every a in input.change.approvals { a.role == "release-manager" }
	msg := "every approval is by a release manager"
}
