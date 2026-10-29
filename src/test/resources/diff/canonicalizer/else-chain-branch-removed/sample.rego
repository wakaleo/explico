package diffcanon

verdict := "blocked" if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
} else := "review" if {
	input.deployment.size > 3
}
