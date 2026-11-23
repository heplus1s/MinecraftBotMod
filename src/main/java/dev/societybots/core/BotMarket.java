package dev.societybots.core;

import java.math.BigDecimal;
import java.util.*;

/** Standalone marketplace state machine. Its repository and listing IDs are not the commodity market's. */
public final class BotMarket {
    private BotMarket() {}
    public enum Stage { LISTED, PAYMENT_PENDING, PAID, SOLD, CANCELLED }
    public enum Receipt { PAID, NOT_PAID, UNKNOWN }
    /** Only confirmed overdue bills count; connection errors and low balance alone do not qualify. */
    public record Hardship(int overduePeriods, BigDecimal outstanding) {
        public Hardship { if(overduePeriods<0 || outstanding.signum()<0) throw new IllegalArgumentException("debt"); }
        public boolean qualifies(int minimum) { return minimum>0 && overduePeriods>=minimum && outstanding.signum()>0; }
    }
    public record Payment(String reference, UUID payer, UUID payee, BigDecimal amount) {}
    public record Listing(UUID id, UUID bot, UUID employer, BigDecimal price, Stage stage,
                          UUID attempt) {
        public Listing {
            Objects.requireNonNull(id); Objects.requireNonNull(bot); Objects.requireNonNull(stage);
            if(price.signum()<=0 || bot.equals(employer)) throw new IllegalArgumentException("listing");
            boolean committed=stage==Stage.PAYMENT_PENDING || stage==Stage.PAID || stage==Stage.SOLD;
            if(committed!=(employer!=null) || committed!=(attempt!=null)) throw new IllegalArgumentException("checkout");
        }
        public Listing reserve(UUID buyer, UUID attemptId, BigDecimal agreedPrice, Employment current, boolean openService) {
            if (openService || stage!=Stage.LISTED || buyer==null || bot.equals(buyer) || attemptId==null || agreedPrice==null
                    || price.compareTo(agreedPrice)!=0 || !isIndependentBot(current,bot)) return this;
            return new Listing(id,bot,buyer,price,Stage.PAYMENT_PENDING,attemptId);
        }
        /** Persist the reservation before submitting this request. Use the identical reference to look up a receipt. */
        public Optional<Payment> payment() {
            return stage!=Stage.PAYMENT_PENDING ? Optional.empty()
                    : Optional.of(new Payment("worker-market:"+id+":"+attempt,employer,bot,price));
        }
        public Listing bankResult(String ref, Receipt receipt) {
            Objects.requireNonNull(receipt);
            var request=payment();
            if (request.isEmpty() || !request.get().reference().equals(ref) || receipt==Receipt.UNKNOWN) return this;
            return receipt==Receipt.PAID ? new Listing(id,bot,employer,price,Stage.PAID,attempt)
                    : new Listing(id,bot,null,price,Stage.LISTED,null);
        }
        /** Persist the result with the contract and controller handoff atomically in the integration layer. */
        public Optional<Conversion> finalizeBuyout(Employment contract, boolean openService) {
            if (openService || stage!=Stage.PAID || !isIndependentBot(contract,bot)) return Optional.empty();
            return Optional.of(new Conversion(new Listing(id,bot,employer,price,Stage.SOLD,attempt),
                    Employment.buyout(id,bot,employer)));
        }
        public Listing cancel(UUID actor) {
            return stage==Stage.LISTED && bot.equals(actor) ? new Listing(id,bot,null,price,Stage.CANCELLED,null) : this;
        }
    }
    public record Conversion(Listing sold, Employment contract) {}
    /** Trusted worker scheduler calls this with the bot's own affirmative decision and fresh debt evidence. */
    public static Optional<Listing> voluntaryOffer(UUID id, Employment current, Hardship hardship,
                                                    int overdueMinimum, boolean botChoosesToList, BigDecimal price) {
        if (!botChoosesToList || current.scheme()!=Employment.Scheme.INDEPENDENT || !hardship.qualifies(overdueMinimum))
            return Optional.empty();
        return Optional.of(new Listing(id,current.bot(),null,price,Stage.LISTED,null));
    }
    private static boolean isIndependentBot(Employment current, UUID bot) {
        return current!=null && current.scheme()==Employment.Scheme.INDEPENDENT && current.bot().equals(bot);
    }
}
