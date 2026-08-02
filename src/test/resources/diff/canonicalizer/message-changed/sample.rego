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
	some stage in input.pipeline.stages
	stage.status != "passed"
	msg := sprintf("stage %v is not green", [stage.name])
}
