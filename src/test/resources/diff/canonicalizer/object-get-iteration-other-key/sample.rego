package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for canonicalizer testing.
# custom:
#   control-id: SAMPLE-001
deny contains msg if {
	some g in object.get(input, "entries", [])
	g.kind == "gold"
	msg := "gold item"
}
