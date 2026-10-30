package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for canonicalizer testing.
# custom:
#   control-id: SAMPLE-001
deny contains msg if {
	# the same iteration, another variable name, a comment and different spacing
	some item in object.get( input, "items", [] )
	item.kind   ==   "gold"
	msg := "gold item"
}
