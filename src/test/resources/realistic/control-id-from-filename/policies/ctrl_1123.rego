package fname_one

import data.fname_nineteen

deny contains msg if {
	not fname_nineteen.signed_off
	msg := "sign-off is missing"
}
