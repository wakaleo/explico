package probes.p13_ref_head_rule

import rego.v1

fruit.apple.seeds := 12

deny if {
	fruit.apple.seeds > 10
}
