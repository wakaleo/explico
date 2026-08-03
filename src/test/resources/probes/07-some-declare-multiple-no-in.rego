package probes.p07_some_declare_multiple_no_in

import rego.v1

deny if {
	some i, j
	arr := input.change.approvals
	arr[i].role == "release-manager"
	arr[j].role == "security"
	i != j
}
