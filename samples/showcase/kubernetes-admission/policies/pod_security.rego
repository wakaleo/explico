# METADATA
# title: Pod security
# description: Admission rules that keep unsafe containers out of the cluster.
package k8s.admission

import rego.v1

# METADATA
# title: No privileged containers
# description: |
#   A container must not run in privileged mode, because it would have
#   near-unrestricted access to the host.
# custom:
#   control-id: K8S-001
#   frameworks:
#     - CIS Kubernetes 5.2.1
deny contains msg if {
	input.request.kind.kind == "Pod"
	some container in input.request.object.spec.containers
	container.securityContext.privileged == true
	msg := sprintf("container %v is privileged", [container.name])
}

# METADATA
# title: Images come from the approved registry
# custom:
#   control-id: K8S-002
deny contains msg if {
	input.request.kind.kind == "Pod"
	some container in input.request.object.spec.containers
	not startswith(container.image, "registry.corp.example/")
	msg := sprintf("image %v is not from the approved registry", [container.image])
}

# METADATA
# title: Required labels are present
# custom:
#   control-id: K8S-003
deny contains msg if {
	input.request.kind.kind == "Pod"
	not input.request.object.metadata.labels.owner
	msg := "pod has no owner label"
}
