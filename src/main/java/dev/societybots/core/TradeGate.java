package dev.societybots.core;

import java.math.BigDecimal;
import java.util.*;

/** Gates all adaptive trading independently of the work scheduler. Only an adapter may settle a trade. */
public final class TradeGate {
    public enum Venue { SHOP, MARKET }
    public enum Side { BUY, SELL }
    public enum Outcome { APPLIED, REJECTED, UNKNOWN }
    public enum Reservation { NEW, ALREADY_FINAL, UNKNOWN }
    public record Scope(Set<String> buy, Set<String> sell, Set<Venue> venues, BigDecimal spendingLimit,
                        BigDecimal minBalance, int maxUnits, long expires) {
        public Scope {
            buy=Set.copyOf(buy); sell=Set.copyOf(sell); venues=Set.copyOf(venues);
            if (spendingLimit.signum()<0 || minBalance.signum()<0 || maxUnits<1 || maxUnits>64 || expires<1)
                throw new IllegalArgumentException("scope");
        }
    }
    public record Quote(Venue venue, Side side, String item, int units, BigDecimal total,
                        UUID counterparty, String fingerprint, UUID orderId) {
        public Quote {
            Objects.requireNonNull(venue); Objects.requireNonNull(side); Objects.requireNonNull(item);
            Objects.requireNonNull(total); Objects.requireNonNull(counterparty); Objects.requireNonNull(fingerprint); Objects.requireNonNull(orderId);
            if (item.isBlank() || units<1 || total.signum()<=0 || fingerprint.isBlank() || fingerprint.length()>400)
                throw new IllegalArgumentException("quote");
        }
    }
    public interface Port {
        BigDecimal botNoteBalance(UUID bot);
        /** Gross spend in the configured budget window; include any outstanding commitments. */
        BigDecimal spentInWindow(UUID bot);
        /** Bot-owned inventory only; never treat personal task cargo or escrow as disposable. */
        int ownedSurplus(UUID bot, String item);
        boolean hasUnresolved(UUID bot);
        /** Atomic, durable insert if absent, scoped to the Bot and server-issued order ID.
         * Never return NEW for an existing ID, even a rejected or changed order.
         * Pending, storage failure, and request mismatches return UNKNOWN.
         */
        Reservation recordPending(UUID bot, UUID tradeId, Quote quote);
        /** Recheck authoritative quote, balance, limits, inventory, and partner rules on the server thread. */
        Outcome executeOnce(UUID bot, UUID tradeId, Quote quote);
        void recordOutcome(UUID bot, UUID tradeId, Outcome outcome);
    }
    private final WorkerControl controller;
    private final UUID bot;
    private final Port port;
    private final CounterVisit visits;
    private Scope scope;
    private boolean uncertain;

    public TradeGate(WorkerControl control, UUID bot, Port port, CounterVisit visits) {
        this.controller=Objects.requireNonNull(control);
        this.bot=Objects.requireNonNull(bot);
        this.port=Objects.requireNonNull(port);
        this.visits=Objects.requireNonNull(visits);
        if (control.owner().filter(bot::equals).isPresent()) throw new IllegalArgumentException("robot must have its own identity");
    }
    public boolean grant(UUID actor, Scope newScope, long now) {
        if (!controller.canControl(actor) || newScope==null || now<0 || now>=newScope.expires()) return false;
        scope=newScope; return true;
    }
    public boolean revoke(UUID actor) {
        if (!controller.canControl(actor)) return false;
        scope=null; return true;
    }
    public boolean clearUncertain(UUID actor) {
        if (!controller.canControl(actor) || port.hasUnresolved(bot)) return false;
        uncertain=false; return true;
    }
    public static CounterVisit.Task counterTask(Quote quote) {
        return new CounterVisit.Task(quote.venue()==Venue.SHOP?CounterVisit.Service.SHOP:CounterVisit.Service.MARKET,
                quote.side().name(),quote.orderId()+":"+quote.fingerprint());
    }
    public Outcome offer(Quote quote, long now, CounterVisit.Proof visit) {
        if (!controller.active() || scope==null || now<0 || now>=scope.expires() || quote==null || uncertain || port.hasUnresolved(bot)
                || (controller.kind()==WorkerControl.Kind.MANAGED && !controller.autonomous())
                || !scope.venues().contains(quote.venue()) || quote.units()>scope.maxUnits()
                || bot.equals(quote.counterparty()) || controller.owner().filter(quote.counterparty()::equals).isPresent())
            return Outcome.REJECTED;
        if (quote.side()==Side.BUY) {
            if (!scope.buy().contains(quote.item())) return Outcome.REJECTED;
            BigDecimal currentSpend=port.spentInWindow(bot);
            BigDecimal balance=port.botNoteBalance(bot);
            if (currentSpend==null || currentSpend.signum()<0 || balance==null
                    || currentSpend.add(quote.total()).compareTo(scope.spendingLimit())>0
                    || balance.subtract(quote.total()).compareTo(scope.minBalance())<0) return Outcome.REJECTED;
        } else if (!scope.sell().contains(quote.item()) || port.ownedSurplus(bot,quote.item())<quote.units()) {
            return Outcome.REJECTED;
        }
        if(!visits.consume(visit,bot,counterTask(quote),now)) return Outcome.REJECTED;
        UUID ref=quote.orderId();
        uncertain=true; // set before any durable call, including one that may write and then throw
        try {
            var reservation=port.recordPending(bot,ref,quote);
            if(reservation==Reservation.ALREADY_FINAL) {
                uncertain=false;
                return Outcome.REJECTED;
            }
            if(reservation!=Reservation.NEW) return Outcome.UNKNOWN;
            var outcome=Objects.requireNonNull(port.executeOnce(bot,ref,quote));
            port.recordOutcome(bot,ref,outcome);
            uncertain=outcome==Outcome.UNKNOWN;
            return outcome;
        } catch(RuntimeException ambiguous) {
            // The caller must reconcile this exact server-issued reference before submitting anything else.
            return Outcome.UNKNOWN;
        }
    }
}
