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
	some s in input.pipeline.stages
	s.status != "passed"
	msg := sprintf("pipeline stage %v did not pass", [s.name])
}
