# METADATA
# title: Release pipeline checks
# description: |
#   `every` says a condition holds for ALL elements of a collection, so it is
#   true when the collection is empty. explico states that on the card, nests
#   the conditions under the head, and renders the block only when everything
#   inside it renders.
package pipeline

import rego.v1

# METADATA
# title: All pipeline checks passed
# description: Every check recorded for the pipeline must have passed.
# custom:
#   control-id: PIPE-001
all_checks_passed if {
	every check in input.pipeline.checks {
		check.status == "passed"
	}
}

# METADATA
# title: Production images are pinned
# description: In production, every container sets an image and does not run privileged.
# custom:
#   control-id: PIPE-002
containers_hardened if {
	input.environment == "production"
	every container in input.containers {
		container.image != ""
		container.privileged == false
	}
}
