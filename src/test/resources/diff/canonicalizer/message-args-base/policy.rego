package diff.sample

import rego.v1

deny contains msg if {
	some c in input.containers
	c.privileged == true
	msg := sprintf("container %v is privileged on %v", [c.name, input.node])
}
