package every.demo

import rego.v1

# METADATA
# scope: document
# title: All checks passed
# description: Every pipeline check must have passed.
# custom:
#   control-id: EV-001
all_checks_ok if {
	every check in input.pipeline.checks {
		check.status == "passed"
	}
}

# METADATA
# scope: document
# title: Labels not empty
# description: Every label value must be non-empty.
# custom:
#   control-id: EV-002
labels_ok if {
	every k, v in input.labels {
		k != ""
		v != ""
	}
}

# METADATA
# scope: document
# title: Containers hardened
# description: Every container sets an image and is not privileged.
# custom:
#   control-id: EV-003
containers_ok if {
	every c in input.containers {
		c.image != ""
		c.privileged == false
	}
}

# METADATA
# scope: document
# title: Groups active
# description: Every member of every group is active.
# custom:
#   control-id: EV-004
groups_ok if {
	every g in input.groups {
		every m in g.members {
			m.active == true
		}
	}
}

# METADATA
# scope: document
# title: Unrenderable body
# description: The body calls a builtin explico has no phrase for, so the whole block stays source.
# custom:
#   control-id: EV-005
body_unrenderable if {
	every c in input.containers {
		json.marshal(c) != ""
	}
}

# METADATA
# scope: document
# title: Literal domain
# description: The collection is a literal, not a path, so the block stays source.
# custom:
#   control-id: EV-006
literal_domain if {
	every x in [1, 2] {
		x > 0
	}
}

# METADATA
# scope: document
# title: Production only
# description: A sibling condition before an every block keeps its place.
# custom:
#   control-id: EV-007
prod_checks_ok if {
	input.env == "production"
	every check in input.pipeline.checks {
		check.status == "passed"
	}
}

exempt_name if {
	input.name == "legacy"
}

# METADATA
# scope: document
# title: Rule reference inside
# description: A rule reference inside an every body stays a link.
# custom:
#   control-id: EV-008
refs_ok if {
	every c in input.containers {
		not exempt_name
		c.image != ""
	}
}
