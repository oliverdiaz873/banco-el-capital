-- Baseline migration: schema intentionally empty in Setup.
-- Domain tables (Account, Operation, Movement, etc.) belong to later implementation phases, not Setup.
-- This file exists so Flyway validation and the migration chain work from day one.
SELECT 1;
