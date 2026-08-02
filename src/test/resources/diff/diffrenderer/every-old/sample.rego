package diff.every

import rego.v1

# METADATA
# scope: document
# title: Every-based check
# custom:
#   control-id: EVERY-001
check_passed if {
	every check in input.pipeline.checks {
		check.status == "passed"
	}
}
