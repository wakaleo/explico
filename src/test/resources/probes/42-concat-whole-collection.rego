package probes.p42_concat_whole_collection

import rego.v1

deny if {
	concat("/", input.change.path_parts) == input.change.full_name
}
