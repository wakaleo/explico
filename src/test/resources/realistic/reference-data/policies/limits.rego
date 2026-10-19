package refdata.limits

import rego.v1

# METADATA
# title: Permitted regions
# description: Deployments are only allowed in the regions the platform team has approved.
# custom:
#   control-id: RD-001
deny contains msg if {
	not input.deployment.region in data.limits.regions
	msg := "region is not approved"
}

# METADATA
# title: Cost centre required
# description: Every deployment names a cost centre known to finance.
# custom:
#   control-id: RD-002
deny contains msg if {
	input.deployment.cost_centre == data.limits.missing
	msg := "cost centre is unknown"
}

# METADATA
# title: Owner on record
# description: The requesting user must be a registered owner.
# custom:
#   control-id: RD-003
deny contains msg if {
	some owner in data.limits.settings.owners
	input.user.name != owner.name
	msg := "user is not a registered owner"
}

# METADATA
# title: Not a platform admin
# description: Platform admins are reviewed elsewhere; this rule reads another rule's set.
# custom:
#   control-id: RD-004
deny contains msg if {
	input.user.name in data.refdata.helpers.admins
	msg := "admins need separate review"
}
