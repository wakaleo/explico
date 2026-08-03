package probes.p12_dynamic_ref_root_var

import rego.v1

deny if {
	some key
	input[key] == "forbidden"
}
