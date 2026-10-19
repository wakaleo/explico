package xpkg

import data.lib.kube
import data.lib.kube as k8s
import rego.v1

# METADATA
# title: Aliased call
# custom:
#   control-id: XP-001
aliased contains msg if {
	some c in input.containers
	kube.is_privileged(c)
	msg := "privileged"
}

# METADATA
# title: Renamed import call, negated
# custom:
#   control-id: XP-002
renamed contains msg if {
	some c in input.containers
	not k8s.has_flag(c.command, "--read-only")
	msg := "missing flag"
}

# METADATA
# title: Full data path call
# custom:
#   control-id: XP-003
full_path contains msg if {
	some c in input.containers
	data.lib.kube.is_privileged(c)
	msg := "privileged"
}

# METADATA
# title: Call in operand position
# custom:
#   control-id: XP-004
operand_position contains msg if {
	some c in input.containers
	kube.image_name(c) == "busybox"
	msg := "busybox"
}
