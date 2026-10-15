package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for canonicalizer testing.
# custom:
#   control-id: SAMPLE-001
deny contains msg if {
	some user in input.users
	some g in data.grants[user.id]
	g.resource == input.resource.id
	msg := "denied"
}
