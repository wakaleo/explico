package probes.p23_count_emptiness_idiom_plain_path

import rego.v1

deny if {
	count(input.change.approvals) == 0
}
