package dev.societybots.core;

import java.math.BigDecimal;
import java.util.*;

/** A fixed job, not a change in ownership. Bank references and progress are persisted by the adapter. */
public record ServiceAgreement(UUID id, UUID customer, UUID worker, MiningWork.Job job,
                               BigDecimal fee, Stage stage) {
    public enum Stage { OFFERED, ACCEPTED, FUNDED, COMPLETED, SETTLED, DECLINED }
    public enum Operation { RESERVE, PAY_WORKER }
    public record BankRequest(String reference, Operation operation, UUID customer, UUID worker, BigDecimal amount) {}
    public ServiceAgreement {
        Objects.requireNonNull(id); Objects.requireNonNull(customer); Objects.requireNonNull(worker);
        Objects.requireNonNull(job); Objects.requireNonNull(fee); Objects.requireNonNull(stage);
        if (customer.equals(worker)||fee.signum()<=0) throw new IllegalArgumentException("service terms");
    }
    public static ServiceAgreement offer(UUID id, UUID customer, UUID worker, MiningWork.Job job, BigDecimal fee) {
        return new ServiceAgreement(id,customer,worker,job,fee,Stage.OFFERED);
    }
    private boolean independent(Employment current) {
        return current!=null && current.scheme()==Employment.Scheme.INDEPENDENT && worker.equals(current.bot());
    }
    /** Executed only by the Bot scheduler after the specific worker decides to accept. */
    public ServiceAgreement decision(UUID actor, Employment current, boolean accepts) {
        if(stage!=Stage.OFFERED || !worker.equals(actor) || !independent(current)) return this;
        return with(accepts?Stage.ACCEPTED:Stage.DECLINED);
    }
    public Optional<BankRequest> payment() {
        var operation=switch(stage){case ACCEPTED->Operation.RESERVE;case COMPLETED->Operation.PAY_WORKER;default->null;};
        return operation==null?Optional.empty():Optional.of(new BankRequest(
                "worker-service:"+id+":"+operation.name(),operation,customer,worker,fee));
    }
    /** On failure or ambiguity, do not issue another reference. The adapter reconciles the original request. */
    public ServiceAgreement confirmed(String reference, boolean confirmed) {
        var pending=payment();
        if(!confirmed || pending.isEmpty() || !pending.get().reference().equals(reference)) return this;
        return with(stage==Stage.ACCEPTED?Stage.FUNDED:Stage.SETTLED);
    }
    public ServiceAgreement recordWorkDone(UUID actor, Employment current, MiningWork.State observed) {
        if(stage!=Stage.FUNDED || !worker.equals(actor) || !independent(current) || observed!=MiningWork.State.COMPLETE)
            return this;
        return with(Stage.COMPLETED);
    }
    private ServiceAgreement with(Stage to) { return new ServiceAgreement(id,customer,worker,job,fee,to); }
}
