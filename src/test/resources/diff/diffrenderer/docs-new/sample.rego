package diff.docs

import rego.v1

# METADATA
# scope: document
# title: Docs control (updated)
# description: Updated description text.
# custom:
#   control-id: DOCS-001
deny contains msg if {
	input.y == 2
	msg := "y is 2"
}
