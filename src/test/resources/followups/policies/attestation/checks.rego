package checks

fix_standard(subject, code) = {"standardId": code, "subject": subject}

is_failing(standards, code) if {
    some s in standards
    s.code == code
    s.status == "failing"
}

reason_for(standards, code, default_reason) = default_reason
