package lib.workloads

import rego.v1

containers contains container if {
	container := input.spec.containers[_]
}

containers contains container if {
	container := input.spec.initContainers[_]
}

limit := 4
