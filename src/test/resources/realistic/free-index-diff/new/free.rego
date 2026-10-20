package free.diff

import rego.v1

# METADATA
# scope: document
# title: Shared index
# custom:
#   control-id: FD-001
join_index if {
	some i
	input.pods[i].name == input.labels[i]
}
