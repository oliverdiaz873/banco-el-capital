-- Read-only inventory for V12 (H-06 / D5).
-- Run against a test or diagnostic database only; never against production.
-- Returns aggregate counts only: no idempotency keys, operation ids, actors,
-- account ids, beneficiary ids or financial amounts.

-- Total rows that would violate:
--   outcome <> 'CONFIRMED' OR operation_id IS NOT NULL
select count(*) as invalid_confirmed_without_operation
from transfer_operation_idempotency
where outcome = 'CONFIRMED'
  and operation_id is null;

-- Same inventory grouped by the optional intent path. NULL is the historical
-- pre-V11 branch and is expected to be inspected manually.
select path, count(*) as invalid_rows
from transfer_operation_idempotency
where outcome = 'CONFIRMED'
  and operation_id is null
group by path
order by path nulls first;
