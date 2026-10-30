package masktypo

import rego.v1

# METADATA
# scope: document
# custom:
#   control-id: MT-1
#   mask:
#     - input.subjectId
#     - Requester.Email
#     - requester.email
leaky contains msg if {
	not input.ok
	msg := sprintf("%v / %v is not ok", [input.subjectId, input.requester.email])
}
