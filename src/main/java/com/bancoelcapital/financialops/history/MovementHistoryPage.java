package com.bancoelcapital.financialops.history;

import java.util.List;

/**
 * One page of confirmed movements plus the opaque cursor that resumes after its last item ({@code
 * null} when the page reached the end of the history). Not a consistent snapshot: the cursor only
 * guarantees exact {@code (createdAt, id)} keyset resumption within a static set (ADR-22).
 */
public record MovementHistoryPage(List<MovementHistoryEntry> items, String nextCursor) {}
