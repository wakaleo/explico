package diff.renamed

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for policy-diff testing.
# custom:
#   control-id: SAMPLE-001
violation contains msg if {
	input.deployment.environment == "staging"
	some stage in input.pipeline.stages
	stage.status != "passed"
	msg := sprintf("pipeline stage %v did not pass", [stage.name])
}
