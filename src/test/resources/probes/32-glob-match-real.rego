package probes.p32_glob_match_real

import rego.v1

deny if {
	glob.match("release/*", ["/"], input.artifact.source_branch)
}
