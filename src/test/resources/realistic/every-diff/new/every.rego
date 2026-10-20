package every.diff

import rego.v1

# METADATA
# scope: document
# title: Nested condition changes
# description: d
# custom:
#   control-id: ED-001
checks_ok if {
	every check in input.pipeline.checks {
		check.status == "ok"
	}
}

# METADATA
# scope: document
# title: Only reformatted
# description: d
# custom:
#   control-id: ED-002
reformatted_ok if {
	every c in input.containers {
		  c.image   !=   ""   # reformatted, same logic
	}
}
