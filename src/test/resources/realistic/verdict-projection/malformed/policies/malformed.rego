package verdict_malformed

# METADATA
# scope: document
# title: Verdict declared as a scalar
# custom:
#   control-id: CTRL-V-BAD1
#   verdict: compliant
bad_scalar := {"compliant": false, "commentary": "No owner recorded"} if {
	not input.owner
} else := {"compliant": true, "commentary": "Owner recorded"}

# METADATA
# scope: document
# title: Verdict without an outcome
# custom:
#   control-id: CTRL-V-BAD2
#   verdict:
#     reason: commentary
bad_no_outcome := {"compliant": false, "commentary": "No owner recorded"} if {
	not input.owner
} else := {"compliant": true, "commentary": "Owner recorded"}
