package com.clinicit.noshow.api;

import java.time.LocalDate;

/**
 * How the advisory flag would have done over past days, each judged as of its own start.
 *
 * @param appointments          booked appointments with an outcome (kept or missed); walk-ins
 *                              and cancellations are left out
 * @param missed                of those, never arrived
 * @param insufficientHistory   not judged: the patient had fewer than 3 earlier outcomes
 * @param flagged               judged Elevated
 * @param flaggedMissed         of the flagged, never arrived
 * @param missRate              missed ÷ appointments: what a flag has to beat
 * @param flaggedMissRate       flaggedMissed ÷ flagged (precision)
 * @param notFlaggedMissRate    miss rate of judged but not flagged appointments
 * @param recall                flaggedMissed ÷ missed: the share of misses the flag caught
 * @param lift                  flaggedMissRate ÷ missRate; 1 means the flag adds nothing
 * @param enoughData            at least 20 flagged and 10 missed: below that, do not draw conclusions
 */
public record NoShowRiskEvaluation(
        LocalDate from,
        LocalDate to,
        long appointments,
        long missed,
        long insufficientHistory,
        long flagged,
        long flaggedMissed,
        Double missRate,
        Double flaggedMissRate,
        Double notFlaggedMissRate,
        Double recall,
        Double lift,
        boolean enoughData
) {
    public static NoShowRiskEvaluation of(LocalDate from, LocalDate to, long appointments, long missed, long unknown,
                                          long flagged, long flaggedMissed, long typical, long typicalMissed) {
        Double missRate = ratio(missed, appointments);
        Double flaggedRate = ratio(flaggedMissed, flagged);
        return new NoShowRiskEvaluation(from, to, appointments, missed, unknown, flagged, flaggedMissed,
                missRate, flaggedRate, ratio(typicalMissed, typical), ratio(flaggedMissed, missed),
                flaggedRate == null || missRate == null || missRate == 0 ? null : flaggedRate / missRate,
                flagged >= 20 && missed >= 10);
    }

    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
