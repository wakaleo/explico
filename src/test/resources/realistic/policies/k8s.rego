# METADATA
# title: Container hardening
# description: Containers must not be privileged.
package k8s.admission

import rego.v1

# METADATA
# title: No privileged containers
# description: Privileged containers are denied.
# custom:
#   control-id: K8S-001
deny contains msg if {
	input.request.kind.kind == "Pod"
	some container in input.request.object.spec.containers
	container.securityContext.privileged == true
	msg := sprintf("container %v is privileged", [container.name])
}

# METADATA
# title: Images from approved registry
# custom:
#   control-id: K8S-002
deny contains msg if {
	input.request.kind.kind == "Pod"
	container := input.request.object.spec.containers[_]
	not startswith(container.image, "registry.corp.example/")
	msg := sprintf("image %v not from approved registry", [container.image])
}

# METADATA
# title: Require labels
# custom:
#   control-id: K8S-003
deny contains msg if {
	required := {"owner", "team"}
	provided := {l | input.request.object.metadata.labels[l]}
	missing := required - provided
	count(missing) > 0
	msg := sprintf("missing labels: %v", [missing])
}

deny contains msg if {
	input.request.object.spec.containers[_].resources.limits == null
	msg := "limits required"
}

is_exempt(ns) if {
	ns in {"kube-system", "kube-public"}
}

deny contains msg if {
	not is_exempt(input.request.namespace)
	input.request.operation in ["CREATE", "UPDATE"]
	endswith(input.request.object.metadata.name, "-test")
	msg := "test names not allowed"
}
