package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for canonicalizer testing.
# custom:
#   control-id: SAMPLE-001
deny contains msg if {
	input.deployment.environment == "production"
	some k, v in input.change.approvals
	k == "pending"
	msg := sprintf("approval %v is pending", [k])
}
