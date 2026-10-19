# METADATA
# title: Legacy admission rules
package legacy.admission

# METADATA
# title: No privileged containers
# custom:
#   control-id: LEG-001
deny[msg] {
	container := input.containers[_]
	container.privileged == true
	msg := sprintf("container %v is privileged", [container.name])
}

# METADATA
# title: Approved registry
# custom:
#   control-id: LEG-002
violation[{"msg": msg}] {
	container := input.containers[_]
	not startswith(container.image, "registry.example/")
	msg := sprintf("image %v is not approved", [container.image])
}

allowed_users = {"alice", "bob"}

# METADATA
# title: Known user
# custom:
#   control-id: LEG-003
deny[msg] {
	not allowed_users[input.user]
	msg := "unknown user"
}
