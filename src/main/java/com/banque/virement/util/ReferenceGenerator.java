package com.banque.virement.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Genere des references metier uniques et lisibles : TRF-2026-000123.
 */
@Component
public class ReferenceGenerator {

    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() % 100000);

    public String nextTransferReference() {
        return "TRF-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", sequence.incrementAndGet() % 1000000);
    }

    public String nextBatchReference() {
        return "BAT-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", sequence.incrementAndGet() % 1000000);
    }
}
