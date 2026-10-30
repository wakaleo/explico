package maskdiff

import rego.v1

# METADATA
# scope: document
# custom:
#   control-id: MD-1
#   mask:
#     - subjectId
#     - requester.email
rule contains msg if {
	not input.ok
	msg := "not ok"
}
