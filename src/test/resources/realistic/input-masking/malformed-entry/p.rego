package badentry

import rego.v1

# METADATA
# scope: document
# custom:
#   control-id: BM-2
#   mask:
#     - subjectId
#     - ""
#     - 7
leaky contains msg if {
	not input.ok
	msg := sprintf("%v is not ok", [input.subjectId])
}
