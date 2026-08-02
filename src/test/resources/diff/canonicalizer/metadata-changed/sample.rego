package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control (renamed)
# description: An updated description text, logic untouched.
# custom:
#   control-id: SAMPLE-001
#   frameworks:
#     - SOC 2 CC8.1
deny contains msg if {
	input.deployment.environment == "production"
	some stage in input.pipeline.stages
	stage.status != "passed"
	msg := sprintf("pipeline stage %v did not pass", [stage.name])
}
