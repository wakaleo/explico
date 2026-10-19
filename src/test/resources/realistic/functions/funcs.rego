package funcs

import rego.v1

is_prod(env) if {
	env == "production"
}

in_region(region, allowed) if {
	region in allowed
}

deny contains msg if {
	is_prod(input.deployment.environment)
	in_region(input.region, ["us-east-1", "eu-west-1"])
	msg := "prod outside approved regions"
}

deny contains msg if {
	in_region(input.region, {r | some r in input.regions; net.cidr_is_valid(r)})
	json.is_valid(input.items)
	msg := "unmappable calls"
}
