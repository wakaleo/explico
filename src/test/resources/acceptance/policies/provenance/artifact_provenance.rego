package release.provenance

import rego.v1

# METADATA
# scope: document
# title: Artifact provenance
# description: |
#   Production artifacts must be built from a release branch and carry
#   a sign-off label identifying who approved the build.
# custom:
#   control-id: REL-003
#   frameworks:
#     - ISO 27001 A.8.32
#     - SLSA v1.0 L2
deny contains msg if {
	input.deployment.environment == "production"
	not startswith(input.artifact.source_branch, "release/")
	msg := sprintf("artifact was built from branch %v, not a release branch", [input.artifact.source_branch])
}

deny contains msg if {
	input.deployment.environment == "production"
	not input.artifact.labels["signed-off-by"]
	msg := "artifact carries no signed-off-by label"
}
