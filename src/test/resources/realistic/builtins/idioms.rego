package idioms

import rego.v1

deny contains msg if {
	count(input.items) == 0
	count(input.roles) > 0
	count(input.tags) != 0
	count(input.labels) > 1
	count(input.rows) >= 1
	count(input.cols) == 5
	count(input.pods[_].containers) == 0
	msg := "count idioms"
}
