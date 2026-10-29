package verdict_outcome_only

# METADATA
# scope: document
# title: Owner required (outcome only)
# custom:
#   control-id: CTRL-V-OUT
#   verdict:
#     outcome: compliant
decision := {"compliant": false, "commentary": "No owner recorded"} if {
	not input.owner
} else := {"compliant": true, "commentary": "Owner recorded"}
