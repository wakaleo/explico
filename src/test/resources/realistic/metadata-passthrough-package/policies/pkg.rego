# METADATA
# scope: package
# title: Ownership package
# description: Package-level METADATA with authors, related resources and extra custom keys.
# authors:
# - Pat Package <pat@example.com>
# related_resources:
# - ref: https://example.com/package-runbook
#   description: Package runbook
# custom:
#   owner: platform-team
#   tags:
#     - tier1
#     - production
#   excluded:
#     reason: not applicable
package pkgmeta

import rego.v1

# METADATA
# scope: document
# title: A rule
# description: Rule-level METADATA is unaffected.
# custom:
#   control-id: PM-001
deny contains msg if {
	input.x == 1
	msg := "x is one"
}
