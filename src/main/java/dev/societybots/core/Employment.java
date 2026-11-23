package dev.societybots.core;

import java.math.BigDecimal;
import java.util.*;

/** Immutable payroll decisions. The adapter must persist both snapshots and bank receipts.
 * Wall time never restarts the period sequence. Offline periods are not treated as failed debits.
 */
public record Employment(UUID contractId, UUID bot, UUID employer, Scheme scheme, Terms terms,
                         long nextPeriod, int consecutiveMisses) {
    public enum Scheme { BUYOUT, WAGED, INDEPENDENT }
    public enum Receipt { PAID, INSUFFICIENT, UNKNOWN, SERVER_UNAVAILABLE }
    public record Terms(BigDecimal wages, long interval, int allowedMisses) {
        public Terms {
            Objects.requireNonNull(wages);
            if (wages.signum()<=0 || interval<1 || allowedMisses<1 || allowedMisses>100)
                throw new IllegalArgumentException("wage terms");
        }
    }
    public record Wage(UUID employer, UUID bot, BigDecimal amount, long period, String reference) {}
    public Employment {
        Objects.requireNonNull(contractId); Objects.requireNonNull(bot); Objects.requireNonNull(scheme);
        if (nextPeriod<0 || consecutiveMisses<0 || bot.equals(employer))
            throw new IllegalArgumentException("invalid contract");
        if ((scheme==Scheme.INDEPENDENT)!=(employer==null) || (scheme==Scheme.WAGED)!=(terms!=null)
                || (scheme!=Scheme.WAGED && (nextPeriod!=0 || consecutiveMisses!=0))
                || (scheme==Scheme.WAGED && consecutiveMisses>=terms.allowedMisses()))
            throw new IllegalArgumentException("invalid payroll state");
    }
    public static Employment waged(UUID contractId,UUID bot,UUID employer,Terms terms,long start) {
        if(start<0) throw new IllegalArgumentException("invalid clock");
        return new Employment(contractId,bot,employer,Scheme.WAGED,terms,Math.addExact(start,terms.interval()),0);
    }
    public static Employment buyout(UUID contractId,UUID bot,UUID owner) {
        return new Employment(contractId,bot,owner,Scheme.BUYOUT,null,0,0);
    }
    /** Request after full period. Never debit more than this reference amount, never debit another user. */
    public Optional<Wage> due(long now) {
        if (scheme!=Scheme.WAGED || now<nextPeriod) return Optional.empty();
        return Optional.of(new Wage(employer,bot,terms.wages(),nextPeriod,"worker-wages:"+contractId+":"+nextPeriod));
    }
    /** An unknown/failed service is not an unpaid period, and blocks treating any later due as a separate failure.
     * To reclassify after terminal confirmed result, persist transition then retire old controller before re-enabling actions.
     */
    public Employment settle(long now,String reference,Receipt result) {
        Objects.requireNonNull(result);
        var wage=due(now);
        if (wage.isEmpty() || !wage.get().reference().equals(reference)
                || result==Receipt.UNKNOWN || result==Receipt.SERVER_UNAVAILABLE) return this;
        int misses=result==Receipt.PAID ? 0 : consecutiveMisses+1;
        if (misses>=terms.allowedMisses())
            return new Employment(contractId,bot,null,Scheme.INDEPENDENT,null,0,0);
        long elapsed=now-nextPeriod;
        long steps=Math.addExact(elapsed/terms.interval(),1);
        long future=Math.addExact(nextPeriod,Math.multiplyExact(steps,terms.interval()));
        return new Employment(contractId,bot,employer,Scheme.WAGED,terms,future,misses);
    }
}
