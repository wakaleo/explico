# METADATA
# scope: package
# authors:
# - Ola Only
package onlyextras

import rego.v1

# METADATA
# scope: document
# title: A rule
# description: Rule-level METADATA is unaffected.
# custom:
#   control-id: OE-001
deny contains msg if {
	input.x == 1
	msg := "x is one"
}
