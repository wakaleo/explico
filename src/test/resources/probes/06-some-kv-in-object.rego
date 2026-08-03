package probes.p06_some_kv_in_object

import rego.v1

deny if {
	some k, v in input.change.metadata
	k == "signed_off_by"
	v == ""
}
