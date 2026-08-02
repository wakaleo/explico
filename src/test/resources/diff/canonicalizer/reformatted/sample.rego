package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for canonicalizer testing.
# custom:
#   control-id: SAMPLE-001
deny contains msg if {
    # Only production deployments are in scope.
    input.deployment.environment == "production"

    # Every pipeline stage must have passed.
    some stage in input.pipeline.stages

    stage.status != "passed" # a stage that didn't pass is a violation

    msg := sprintf("pipeline stage %v did not pass", [stage.name])
}
