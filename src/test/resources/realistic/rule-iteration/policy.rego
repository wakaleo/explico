package riter

import data.lib.workloads
import data.lib.workloads as wl
import rego.v1

local_items contains item if {
	item := input.items[_]
}

# METADATA
# title: Wildcard assignment over an imported partial rule
# custom:
#   control-id: RI-001
imported_wildcard contains msg if {
	container := workloads.containers[_]
	container.securityContext.privileged == true
	msg := "privileged"
}

# METADATA
# title: some-in over an imported partial rule
# custom:
#   control-id: RI-002
imported_some_in contains msg if {
	some container in workloads.containers
	container.image == "busybox"
	msg := "busybox"
}

# METADATA
# title: Renamed import
# custom:
#   control-id: RI-003
renamed_import contains msg if {
	container := wl.containers[_]
	container.name == "app"
	msg := "app"
}

# METADATA
# title: Same-package partial rule
# custom:
#   control-id: RI-004
same_package contains msg if {
	item := local_items[_]
	item.enabled == true
	msg := "enabled"
}

# METADATA
# title: Field access directly on the rule value
# custom:
#   control-id: RI-005
direct_field contains msg if {
	workloads.containers[_].name == "sidecar"
	msg := "sidecar"
}
