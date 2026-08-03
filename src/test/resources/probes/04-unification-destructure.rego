package probes.p04_unification_destructure

import rego.v1

deny if {
	[x, y] = [input.change.author, input.change.approver]
	x == y
}
