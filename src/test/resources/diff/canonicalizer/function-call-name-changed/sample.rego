package diff.functioncall

import rego.v1

f(x) := 1 if x == input.limit

g(x) := 2 if x == input.limit

deny contains msg if {
	g(input.a) > 1
	msg := "m"
}
