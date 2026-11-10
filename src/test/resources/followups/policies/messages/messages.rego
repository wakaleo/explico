package message_flows

import data.shared.compliant
import data.shared.non_compliant

max_count := 3

over_percent(v, t) if v * 100 > t * 10

decision(label, violations, total, fixes) := non_compliant(sprintf("Too many %s data flows are non-authoritative", [label]), fixes, []) if {
    violations > max_count
} else := non_compliant(sprintf("Too high a proportion of %s data flows are non-authoritative", [label]), fixes, []) if {
    over_percent(violations, total)
} else := compliant(sprintf("Some %s data flows were not authoritative", [label]), fixes, []) if {
    violations > 0
} else := compliant(sprintf("All %s data flows are from authoritative sources", [label]), [], [])

# A %d verb is not understood: the message stays as the author's own source, never a misaligned one.
counted(n) := non_compliant(sprintf("%d flows", [n]), [], []) if {
    n > 1
} else := compliant("fine", [], [])

# More arguments than verbs.
excess(n) := non_compliant(sprintf("flows", [n]), [], []) if {
    n > 1
} else := compliant("fine", [], [])

# A plain field of the input as an argument.
from_input := non_compliant(sprintf("service %v is unhealthy", [input.service.name]), [], []) if {
    input.service.unhealthy
} else := compliant("ok", [], [])

# An indexed argument has no established phrase.
indexed := non_compliant(sprintf("first is %v", [input.items[0]]), [], []) if {
    input.flag
} else := compliant("ok", [], [])

# Arguments that are not paths or parameters stay as source.
literal_arg := non_compliant(sprintf("limit %v", [3]), [], []) if {
    input.flag
} else := compliant("ok", [], [])

# A function with literal-argument patterns.
shown_percent(_, 0) := 0

shown_percent(violations, total) := round(violations * 100 / total) if total > 0

# A body with no conditions at all, among others.
tiers(_) := "none"

tiers(x) := "some" if x > 0
