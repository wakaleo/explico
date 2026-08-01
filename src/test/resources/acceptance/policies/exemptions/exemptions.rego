package release.exemptions

import rego.v1

exempt_service if {
	input.deployment.service in data.release.exempt_services
}
