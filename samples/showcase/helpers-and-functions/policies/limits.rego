# METADATA
# title: Resource limits
# description: Shows helper rules, constants and user-defined functions.
package limits

import rego.v1

# METADATA
# title: Maximum replicas
max_replicas := 10

# METADATA
# title: Is this a large workload?
# description: Parameters are named in the card; calls to it are linked.
is_large(workload) if {
	workload.replicas > 5
}

# METADATA
# title: Large workloads need a budget code
# custom:
#   control-id: LIM-001
missing_budget_code contains msg if {
	some workload in input.workloads
	is_large(workload)
	not workload.budget_code
	msg := "large workload has no budget code"
}

# METADATA
# title: Replica ceiling
# custom:
#   control-id: LIM-002
too_many_replicas contains msg if {
	input.replicas > max_replicas
	msg := sprintf("%v replicas requested", [input.replicas])
}
