package diffcanon

verdict := "review" if {
	input.deployment.size > 3
} else := "blocked" if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
} else := "allowed"
