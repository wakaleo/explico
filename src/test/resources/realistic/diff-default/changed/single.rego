package single

import rego.v1

default allow := true

allow if {
	input.ok
}
