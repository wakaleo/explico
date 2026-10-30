package badmask

import rego.v1

# METADATA
# scope: document
# custom:
#   control-id: BM-1
#   mask: subjectId
leaky contains msg if {
	not input.ok
	msg := sprintf("%v is not ok", [input.subjectId])
}
