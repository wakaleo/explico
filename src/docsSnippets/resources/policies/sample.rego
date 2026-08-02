package docs.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control for the docs-snippets illustration
# custom:
#   control-id: DOCS-001
deny contains msg if {
	input.x == 1
	msg := "x is 1"
}
