package release.evidence

import rego.v1

import data.release.exemptions

# METADATA
# scope: document
# title: Pipeline evidence
# description: |
#   Every pipeline stage must have passed before a release candidate
#   may be deployed, unless the service is explicitly exempt.
# custom:
#   control-id: REL-002
#   frameworks:
#     - SOC 2 CC8.1
deny_stage contains msg if {
	is_release_candidate
	not exemptions.exempt_service
	some stage in input.pipeline.stages
	stage.status != "passed"
	msg := sprintf("pipeline stage %v did not pass", [stage.name])
}

# METADATA
# title: Release candidate environments
# description: Deployments to production or staging count as release candidates.
is_release_candidate if {
	input.deployment.environment in {"production", "staging"}
}
