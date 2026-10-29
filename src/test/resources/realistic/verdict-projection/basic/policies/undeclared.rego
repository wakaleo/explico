package verdict_undeclared

# METADATA
# scope: document
# title: Owner required (no declaration)
# custom:
#   control-id: CTRL-V-RAW
#   frameworks:
#     - Internal Control Standard
decision := {"compliant": false, "commentary": "No owner recorded"} if {
	not input.owner
} else := {"compliant": true, "commentary": "Owner recorded"}
