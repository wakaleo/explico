package diff.defaultandbody

import rego.v1

# METADATA
# scope: document
# title: Default and body control
# custom:
#   control-id: DEFBODY-001
default allow := true

allow if {
	input.x == 1
}
