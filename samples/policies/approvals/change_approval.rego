package release.approvals

import rego.v1

# METADATA
# scope: document
# title: Production change approval
# description: |
#   Production deployments must reference an approved change ticket,
#   and the change author must not approve their own change.
# custom:
#   control-id: REL-001
#   frameworks:
#     - SOC 2 CC8.1
#     - ISO 27001 A.8.32
deny contains msg if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
	msg := sprintf("release %v has no approved change ticket", [input.deployment.id])
}

deny contains msg if {
	input.deployment.environment == "production"
	input.change.author == input.change.approver
	msg := sprintf("change %v was approved by its author", [input.change.id])
}
