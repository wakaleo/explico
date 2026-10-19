# METADATA
# title: Constructs explico will not paraphrase
# description: |
#   Each rule uses a construct explico does not render as prose. It shows the
#   verbatim Rego instead, clearly marked, and counts it in the coverage footer.
package fallbacks

import rego.v1

# METADATA
# title: At most three distinct regions
# custom:
#   control-id: FB-001
too_many_regions contains msg if {
	count({r | some r in input.regions}) > 3
	input.environment == "production"
	msg := "too many regions"
}

# METADATA
# title: Every container has a limit
# custom:
#   control-id: FB-002
unlimited_container contains msg if {
	input.kind == "Pod"
	not every_container_limited
	msg := "a container has no limit"
}

every_container_limited if {
	every c in input.containers {
		c.limit
	}
}
