package release.governance

import rego.v1

# METADATA
# scope: document
# title: Release governance evidence (updated)
# description: |
#   Updated description text only -- production releases require a release
#   manager approval, must not fall inside a freeze window, and all mandatory
#   checks must have passed.
# custom:
#   control-id: REL-004
#   frameworks:
#     - SOC 2 CC8.1
#     - DORA Art. 9
deny contains msg if {
	input.deployment.environment == "production"
	count({a | some a in input.change.approvals; a.role == "release-manager"}) == 0
	msg := "no release manager approval is recorded"
}

deny contains msg if {
	input.deployment.environment == "production"
	some window in data.release.freeze_windows
	input.deployment.timestamp >= window.start
	input.deployment.timestamp <= window.finish
	msg := sprintf("deployment falls inside freeze window %v", [window.name])
}

deny contains msg if {
	input.deployment.environment == "production"
	not all_checks_passed
	msg := "not all mandatory checks passed"
}

# METADATA
# title: Mandatory check completeness
all_checks_passed if {
	every check in input.pipeline.checks {
		check.status == "passed"
	}
}
