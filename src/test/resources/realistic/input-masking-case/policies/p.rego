package maskcase

import rego.v1

# METADATA
# scope: document
# title: Blocked users are named in lower case
# custom:
#   control-id: MC-1
#   mask:
#     - email
blocked contains msg if {
	not input.ok
	msg := sprintf("%v is blocked", [lower(input.email)])
}
