package probes.p11_walk_builtin

import rego.v1

deny if {
	some path, value
	walk(input, [path, value])
	value == "secret"
}
