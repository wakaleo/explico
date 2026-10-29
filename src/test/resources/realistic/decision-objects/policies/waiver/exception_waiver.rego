# Note: `scope: document` METADATA sits directly above the rule it describes
# (see FR-6), never above `package` - opa rejects the latter.
package exception_waiver

import data.shared.compliant
import data.shared.non_compliant
import data.actions.complete_form

ns_per_day = 24 * 60 * 60 * 1000000000

has_request := count(input.waiverRequestHistory) > 0
current_request := input.waiverRequestHistory[0]
current_request_subject := concat(":", ["fact", current_request.id]) if has_request

first_request_ts_ns := min([time.parse_rfc3339_ns(r.timestamp) |
    some r in input.waiverRequestHistory]) if has_request

days_since_first_request := (input.now - first_request_ts_ns) / ns_per_day if has_request

requires_senior := days_since_first_request >= input.config.thresholds.requireSeniorAfterDays
requires_board := days_since_first_request >= input.config.thresholds.requireBoardAfterDays

current_self   := [s | some s in input.selfSignoffs;   s.subject == current_request_subject]
current_senior := [s | some s in input.seniorSignoffs; s.subject == current_request_subject]
current_board  := [s | some s in input.boardSignoffs;  s.subject == current_request_subject]

approved_self   := [s | some s in current_self;   s.value.decision == "approved"]
approved_senior := [s | some s in current_senior; s.value.decision == "approved"]
approved_board  := [s | some s in current_board;  s.value.decision == "approved"]

within_self_window if { days_since_first_request < input.config.thresholds.selfCoversDays }
within_senior_window if { days_since_first_request < input.config.thresholds.seniorCoversDays }

target_date_ns := time.parse_rfc3339_ns(concat("", [current_request.value.targetDate, "T00:00:00Z"])) if has_request

self_valid if { count(approved_self) > 0; within_self_window } else := false
senior_valid if { count(approved_senior) > 0; within_senior_window } else := false
board_valid if { count(approved_board) > 0; input.now < target_date_ns } else := false
self_and_senior_valid if { self_valid; senior_valid } else := false
all_signoffs_valid if { self_valid; senior_valid; board_valid } else := false

request_action := complete_form(input.subjectId, "waiver.request", [])
self_action    := complete_form(current_request_subject, "waiver.signoff.self", [])
senior_action  := complete_form(current_request_subject, "waiver.signoff.senior", [])
board_action   := complete_form(current_request_subject, "waiver.signoff.board", [])

self_action_list   := [self_action]   if within_self_window   else := []
senior_action_list := [senior_action] if within_senior_window else := []

# METADATA
# scope: document
# title: Exception waiver decision
# description: |
#   Decides whether a non-compliant subject is covered by a valid waiver. A
#   waiver must be requested, then signed off. The sign-off level required
#   escalates with how long the non-compliance has run: self-attestation first,
#   then a senior approver, then a governance board once it outlasts the
#   configured thresholds. Coverage from each level lasts only for its window.
# authors:
#   - Control Platform Team
# custom:
#   control-id: CTRL-WAIVER
#   frameworks:
#     - Internal Control Standard
#   verdict:
#     outcome: compliant
#     reason: commentary
#   actions:
#     at: actions
#     label: action.predicate
decision = non_compliant("No waiver requested", [request_action], []) if {
    not has_request
} else = non_compliant("Board sign-off required",
    array.concat(array.concat(self_action_list, senior_action_list), [board_action]), []) if {
    requires_board
    not all_signoffs_valid
} else = non_compliant("Senior sign-off required",
    array.concat(self_action_list, senior_action_list), []) if {
    requires_senior
    not self_and_senior_valid
} else = non_compliant("Self-attestation required", self_action_list, []) if {
    not self_valid
} else = compliant("Non-compliance waived", [], [])
