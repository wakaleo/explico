package verdict_diff

# METADATA
# scope: document
# title: Owner required
# custom:
#   control-id: CTRL-V-DIFF
#   verdict:
#     outcome: compliant
#     reason: summary
decision := {"compliant": false, "commentary": "No owner recorded", "summary": "none"} if {
	not input.owner
} else := {"compliant": true, "commentary": "Owner recorded", "summary": "ok"}
