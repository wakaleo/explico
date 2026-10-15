package diff.sample

import rego.v1

# METADATA
# scope: document
# title: Sample control
# description: A sample rule for canonicalizer testing.
# custom:
#   control-id: SAMPLE-001
deny contains msg if {
	some u in input.users
	some grant in data.grants[u.name]
	grant.resource == input.resource.id
	msg := "denied"
}
