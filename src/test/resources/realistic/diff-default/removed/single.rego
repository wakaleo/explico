package single

import rego.v1

default allow := false

allow if {
	input.ok
}
