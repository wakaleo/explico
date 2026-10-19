package lib.kube

import rego.v1

# METADATA
# title: Is the container privileged?
is_privileged(container) if {
	container.securityContext.privileged == true
}

# METADATA
# title: Does the command carry a flag?
has_flag(command, flag) if {
	some part in command
	part == flag
}

# METADATA
# title: Normalised image name
image_name(container) := container.image
