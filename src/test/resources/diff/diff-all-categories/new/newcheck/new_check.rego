package release.newcheck

import rego.v1

# METADATA
# scope: document
# title: New deployment window check
# description: Deployments must not be scheduled outside the approved maintenance window.
# custom:
#   control-id: REL-005
deny contains msg if {
	input.deployment.environment == "production"
	not input.deployment.within_maintenance_window
	msg := "deployment scheduled outside the approved maintenance window"
}
