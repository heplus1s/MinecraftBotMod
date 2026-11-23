package dev.societybots.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/** Approval resides entirely in BotMod; the real checkout adapter must use ordinary player settlement. */
public final class LandRequests {
    private LandRequests() {}
    public record Chunk(int x,int z) {}
    public enum Stage { REQUESTED, APPROVED, PENDING, DONE, REJECTED }
    public record Quote(String dimension, Set<Chunk> chunks, BigDecimal centralNotes, String fingerprint) {
        public Quote {
            Objects.requireNonNull(dimension); Objects.requireNonNull(centralNotes); Objects.requireNonNull(fingerprint);
            chunks=Set.copyOf(chunks);
            if (dimension.isBlank() || chunks.isEmpty() || chunks.size()>32 || centralNotes.signum()<=0 || fingerprint.isBlank())
                throw new IllegalArgumentException("land quote");
        }
        public boolean matches(Quote now) {
            return now!=null && dimension.equals(now.dimension) && chunks.equals(now.chunks)
                    && centralNotes.compareTo(now.centralNotes)==0 && fingerprint.equals(now.fingerprint);
        }
    }
    public record Request(UUID id, UUID bot, Quote quote, long expiresAt, Stage stage) {
        public Request {
            Objects.requireNonNull(id); Objects.requireNonNull(bot); Objects.requireNonNull(quote); Objects.requireNonNull(stage);
            if(expiresAt<1) throw new IllegalArgumentException("expiry");
        }
        public Optional<String> checkoutRef() {
            return stage==Stage.PENDING?Optional.of("resident-land:"+id):Optional.empty();
        }
        public Request review(UUID actor, Predicate<UUID> administrator, boolean approve, long now) {
            if(stage!=Stage.REQUESTED || actor==null || actor.equals(bot) || !administrator.test(actor) || now<0 || now>=expiresAt)
                return this;
            return with(approve?Stage.APPROVED:Stage.REJECTED);
        }
        /** Caller must atomically persist PENDING before submitting, using checkoutRef as the idempotency key. */
        public CounterVisit.Task counterTask() {
            return new CounterVisit.Task(CounterVisit.Service.LAND,"purchase",id+":"+quote.fingerprint());
        }
        public Request reserve(Employment current, Quote fresh, BigDecimal botBalance, long now,
                               CounterVisit visits, CounterVisit.Proof visit) {
            if(stage!=Stage.APPROVED || now<0 || now>=expiresAt || !independent(current,bot)
                    || !quote.matches(fresh) || botBalance==null || botBalance.compareTo(quote.centralNotes)<0)
                return this;
            if(visits==null||!visits.consume(visit,bot,counterTask(),now)) return this;
            return with(Stage.PENDING);
        }
        public Request settle(String reference, BotMarket.Receipt receipt) {
            if(!checkoutRef().filter(reference::equals).isPresent() || receipt==BotMarket.Receipt.UNKNOWN) return this;
            return with(receipt==BotMarket.Receipt.PAID?Stage.DONE:Stage.REJECTED);
        }
        private Request with(Stage next) { return new Request(id,bot,quote,expiresAt,next); }
    }
    public static Optional<Request> create(UUID id, Employment current, Quote quote, long expiresAt) {
        if(current==null || !independent(current,current.bot())) return Optional.empty();
        return Optional.of(new Request(id,current.bot(),quote,expiresAt,Stage.REQUESTED));
    }
    private static boolean independent(Employment state, UUID bot) {
        return state!=null && state.bot().equals(bot) && state.scheme()==Employment.Scheme.INDEPENDENT;
    }
}
