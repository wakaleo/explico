package diff.sample

import rego.v1

deny contains msg if {
	some box in input.containers
	box.privileged == true
	msg := sprintf("container %v is privileged on %v", [box.name, input.node])
}
