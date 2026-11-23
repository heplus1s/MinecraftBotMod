package dev.societybots.core;

import dev.societybots.core.api.ResidentApi;
import java.util.*;
import static dev.societybots.core.Routes.*;
import static dev.societybots.core.MiningWork.*;

public final class Checks {
    private static final UUID OWNER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static Pos p(int x) { return new Pos(x, 20, 0); }
    private static Node n(int x, Mode mode) { return new Node(p(x), mode); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void route(boolean loaded, boolean allowed, int maximum, Routes.State expected) {
        var tiles = List.of(n(0, Mode.WALK),n(0, Mode.RAIL),n(1, Mode.RAIL),n(2, Mode.RAIL),
                n(2, Mode.WALK),n(2, Mode.BOAT),n(3, Mode.BOAT),n(4, Mode.BOAT),n(4,Mode.WALK));
        var view = new View() {
            public boolean loaded(Pos pos) { return loaded || !pos.equals(p(3)); }
            public boolean permitted(Node node) { return allowed || !node.equals(n(2,Mode.BOAT)); }
            public List<Arc> observedEdges(Node node) {
                check(loaded(node.pos()), "queried an unloaded cell");
                int idx = tiles.indexOf(node);
                return idx < tiles.size()-1 ? List.of(new Arc(tiles.get(idx+1),1)) : List.of();
            }
        };
        var route = new Routes(view, tiles.get(0),p(4),maximum);
        Routes.State status;
        do { status = route.step(2); } while (status == Routes.State.SEARCHING);
        check(status == expected, "route " + status);
        if (status == Routes.State.FOUND) check(route.path().equals(tiles),"missing transfer");
    }
    private static void inferredTransport(boolean continuous, Routes.State expected) {
        var none = TransportView.Waterway.NONE;
        var ice = TransportView.Waterway.GUARDED_ICE;
        var cells = Map.of(
                p(0), new TransportView.Cell(true,Set.of(p(1)),true,none,false,false),
                p(1), new TransportView.Cell(false,Set.of(p(0),p(2)),false,none,false,false),
                p(2), new TransportView.Cell(true,Set.of(p(1)),true,ice,true,true),
                p(3), new TransportView.Cell(false,Set.of(),false,continuous ? ice : none,true,false),
                p(4), new TransportView.Cell(true,Set.of(),false,ice,true,true));
        var world = new TransportView.World() {
            public boolean isLoaded(Pos pos) { return cells.containsKey(pos); }
            public TransportView.Cell at(Pos pos) {
                check(isLoaded(pos),"read of missing chunk");
                return cells.get(pos);
            }
            public boolean allows(Node node) { return true; }
        };
        var routes = new Routes(new TransportView(world),n(0,Mode.WALK),p(4),100);
        Routes.State result;
        do { result = routes.step(2); } while (result == Routes.State.SEARCHING);
        check(result==expected,"inferred route");
        if (expected==Routes.State.FOUND) {
            check(routes.path().contains(n(1,Mode.RAIL)) && routes.path().contains(n(3,Mode.BOAT)),"inferred journeys");
        }
    }
    private static class Sim implements Port {
        final Deque<Target> pending = new ArrayDeque<>();
        boolean land = true, container = true, full, stale, furnaceReady;
        int diamondBag, copperBag, storedDiamonds, storedIngots, visits, halts;
        public String dimension() { return "minecraft:overworld"; }
        public boolean isLoaded(Pos pos) { return true; }
        public boolean mayBreak(Pos pos) { return land; }
        public boolean mayUse(Pos pos) { return container; }
        public boolean shouldReturnToHub() { return false; }
        public Optional<Target> chooseNext(Area area) { return Optional.ofNullable(pending.peek()); }
        public Optional<Target> observe(Pos pos) {
            if (!stale) return Optional.ofNullable(pending.peek());
            return Optional.of(new Target(pos,Material.STONE,Origin.PLACED,99));
        }
        public Result travelTo(Pos dest) { visits++; return Result.DONE; }
        public Result mine(Target token) {
            pending.removeFirst();
            if (token.material() == Material.DIAMOND_ORE) diamondBag++;
            if (token.material() == Material.COPPER_ORE) copperBag++;
            return Result.DONE;
        }
        public Result deliver(Pos furnace, Pos chest, boolean finalTrip) {
            if (full) return Result.BLOCKED;
            if (copperBag > 0 && !furnaceReady) return Result.WAIT;
            storedDiamonds += diamondBag; diamondBag=0;
            storedIngots += copperBag; copperBag=0;
            return Result.DONE;
        }
        public void halt() { halts++; }
    }
    private static Job job() { return new Job("minecraft:overworld", new Area(p(0),p(5)),p(0),p(8),p(9),p(10),2); }
    private static Target target(int x, Material item, Origin origin) { return new Target(p(x),item,origin,1); }
    private static void blocked(Target t, boolean land, boolean stale) {
        var sim = new Sim(); sim.land=land; sim.stale=stale; sim.pending.add(t);
        var task = new MiningWork(OWNER, sim);
        check(task.start(OWNER, job()), "start");
        task.tick(); task.tick();
        check(task.state() == MiningWork.State.BLOCKED && task.breaks() == 0 && sim.pending.size() == 1, "unsafe break");
    }
    private static void controlModes() {
        var personalSim = new Sim();
        var personal = WorkerControl.player(OWNER,personalSim);
        check(!personal.enableManaged(OWNER) && !personal.order(OTHER,job()),"personal mode authority");
        personal.tick(400);
        check(personal.state()==MiningWork.State.IDLE && personalSim.visits==0,"personal must not self start");
        check(personal.order(OWNER,job()),"personal order");
        personal.tick(401);
        check(personalSim.visits==1,"explicit personal order must run");

        var managedSim = new Sim();
        var liveAdmin = new java.util.concurrent.atomic.AtomicBoolean(true);
        var managed = WorkerControl.managed(managedSim,id->OWNER.equals(id)&&liveAdmin.get());
        check(managed.owner().isEmpty() && !managed.enableManaged(OWNER),"managed starts opt-in");
        check(!managed.configureManaged(OTHER,List.of(job()),20) && managed.configureManaged(OWNER,List.of(job()),20),"managed config ACL");
        check(!managed.order(OWNER,new Job("minecraft:overworld",job().mine(),p(0),p(8),p(9),p(11),2)),"deny unapproved managed job");
        check(managed.enableManaged(OWNER),"enable managed");
        managed.tick(500);
        check(managedSim.visits==1 && !managed.stop(OTHER),"managed auto start, unauthorized stop");
        liveAdmin.set(false);
        check(!managed.stop(OWNER),"administrator role must be rechecked");
        liveAdmin.set(true);
        check(managed.stop(OWNER),"stop managed");
        managed.tick(10_000);
        check(!managed.autonomous() && managed.state()==MiningWork.State.STOPPED && managedSim.visits==1,"manual stop must disable future dispatch");
    }
    private static void trade() {
        var ctl=WorkerControl.player(OWNER,new Sim());
        UUID bot=UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        class TradeSim implements TradeGate.Port {
            int submissions;
            final Set<UUID> journaled=new HashSet<>();
            boolean unresolved, failNext, throwAfterJournal;
            java.math.BigDecimal spent=money("0");
            public java.math.BigDecimal botNoteBalance(UUID id) { check(id.equals(bot),"wrong account"); return money("10"); }
            public java.math.BigDecimal spentInWindow(UUID id) { check(id.equals(bot),"wrong budget"); return spent; }
            public int ownedSurplus(UUID id,String item) { return 0; }
            public boolean hasUnresolved(UUID id) { return unresolved; }
            public TradeGate.Reservation recordPending(UUID id,UUID ref,TradeGate.Quote q) {
                check(id.equals(bot) && q.orderId().equals(ref),"stable order id");
                if(!journaled.add(ref)) return TradeGate.Reservation.ALREADY_FINAL;
                unresolved=true;
                if(throwAfterJournal) throw new IllegalStateException("response unavailable");
                return TradeGate.Reservation.NEW;
            }
            public TradeGate.Outcome executeOnce(UUID id,UUID ref,TradeGate.Quote q) {
                check(unresolved && id.equals(bot),"submission not journaled or wrong actor");
                submissions++;
                if (failNext) return TradeGate.Outcome.UNKNOWN;
                spent=spent.add(q.total()); return TradeGate.Outcome.APPLIED;
            }
            public void recordOutcome(UUID id,UUID ref,TradeGate.Outcome result) { unresolved=result==TradeGate.Outcome.UNKNOWN; }
        }
        var sim=new TradeSim();
        var site=UUID.randomUUID();
        var home=new CounterVisit.Point("overworld",0,64,0);
        var deployment=new CounterVisit.Deployment(site,0,CounterVisit.Service.SHOP,home,4);
        var visits=new CounterVisit((id,point,task)->id.equals(bot)?new CounterVisit.Observation(home,deployment,true,true,true,SESSION):null);
        var gate=new TradeGate(ctl,bot,sim,visits);
        var buy=new TradeGate.Quote(TradeGate.Venue.SHOP,TradeGate.Side.BUY,"minecraft:bread",1,money("3"),OTHER,"q",UUID.randomUUID());
        var ownerTrade=new TradeGate.Quote(TradeGate.Venue.MARKET,TradeGate.Side.BUY,buy.item(),1,money("3"),OWNER,"q2",UUID.randomUUID());
        var sell=new TradeGate.Quote(TradeGate.Venue.SHOP,TradeGate.Side.SELL,"minecraft:copper_ingot",1,money("2"),OTHER,"q3",UUID.randomUUID());
        var scope=new TradeGate.Scope(Set.of(buy.item()),Set.of(sell.item()),
                Set.of(TradeGate.Venue.SHOP,TradeGate.Venue.MARKET),money("6"),money("4"),2,100);
        check(gate.offer(buy,1,visits.atDeployment(bot,site,TradeGate.counterTask(buy),1).orElse(null))==TradeGate.Outcome.REJECTED && !gate.grant(OTHER,scope,1),"default deny and owner ACL");
        check(gate.grant(OWNER,scope,1) && gate.offer(ownerTrade,2,visits.atDeployment(bot,site,TradeGate.counterTask(ownerTrade),2).orElse(null))==TradeGate.Outcome.REJECTED
                && gate.offer(sell,2,visits.atDeployment(bot,site,TradeGate.counterTask(sell),2).orElse(null))==TradeGate.Outcome.REJECTED,"owner trades and cargo denied");
        check(gate.offer(buy,2,null)==TradeGate.Outcome.REJECTED && sim.submissions==0,"no remote transaction");
        check(gate.offer(buy,2,visits.atDeployment(bot,site,TradeGate.counterTask(buy),2).orElse(null))==TradeGate.Outcome.APPLIED && sim.submissions==1,"permitted buy");
        check(gate.offer(buy,3,visits.atDeployment(bot,site,TradeGate.counterTask(buy),3).orElseThrow())==TradeGate.Outcome.REJECTED
                && sim.submissions==1,"new arrival proof does not replay an order");
        var restarted=new TradeGate(ctl,bot,sim,visits);
        check(restarted.grant(OWNER,scope,3) && restarted.offer(buy,3,visits.atDeployment(bot,site,TradeGate.counterTask(buy),3).orElseThrow())
                ==TradeGate.Outcome.REJECTED && sim.submissions==1,"durable order id across gate recreation");
        check(gate.offer(buy,100,visits.atDeployment(bot,site,TradeGate.counterTask(buy),100).orElse(null))==TradeGate.Outcome.REJECTED && gate.revoke(OWNER)
                && gate.offer(buy,3,visits.atDeployment(bot,site,TradeGate.counterTask(buy),3).orElse(null))==TradeGate.Outcome.REJECTED,"expiry and revoke");
        check(gate.grant(OWNER,scope,3),"grant again");
        sim.spent=money("6");
        check(gate.offer(buy,4,visits.atDeployment(bot,site,TradeGate.counterTask(buy),4).orElse(null))==TradeGate.Outcome.REJECTED,"spend cap");
        sim.spent=money("3"); sim.failNext=true;
        var next=new TradeGate.Quote(buy.venue(),buy.side(),buy.item(),buy.units(),buy.total(),buy.counterparty(),
                buy.fingerprint(),UUID.randomUUID());
        check(gate.offer(next,4,visits.atDeployment(bot,site,TradeGate.counterTask(next),4).orElseThrow())==TradeGate.Outcome.UNKNOWN
                && gate.offer(next,5,visits.atDeployment(bot,site,TradeGate.counterTask(next),5).orElseThrow())==TradeGate.Outcome.REJECTED
                && sim.submissions==2,"no retry when original result unknown");
        sim.unresolved=false;check(gate.clearUncertain(OWNER),"reconciled payment");
        sim.throwAfterJournal=true;
        var write=new TradeGate.Quote(buy.venue(),buy.side(),buy.item(),buy.units(),buy.total(),buy.counterparty(),
                buy.fingerprint(),UUID.randomUUID());
        check(gate.offer(write,6,visits.atDeployment(bot,site,TradeGate.counterTask(write),6).orElseThrow())==TradeGate.Outcome.UNKNOWN
                && gate.offer(write,7,visits.atDeployment(bot,site,TradeGate.counterTask(write),7).orElseThrow())==TradeGate.Outcome.REJECTED
                && sim.submissions==2,"journal may commit and then throw; do not retry");
    }
    private static java.math.BigDecimal money(String s) { return new java.math.BigDecimal(s); }
    private static void payroll() {
        UUID bot=UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        var terms=new Employment.Terms(money("2.5"),100,3);
        var buyout=Employment.buyout(OTHER,bot,OWNER);
        check(buyout.due(10_000).isEmpty(),"buyout recurring wage");
        var contract=Employment.waged(OTHER,bot,OWNER,terms,0);
        check(contract.due(99).isEmpty(),"early debit");
        String ref=contract.due(100).orElseThrow().reference();
        check(contract==contract.settle(100,ref,Employment.Receipt.UNKNOWN),"unconfirmed isn't a miss");
        check(contract==contract.settle(100,ref,Employment.Receipt.SERVER_UNAVAILABLE),"bank outage isn't a miss");
        contract=contract.settle(100,ref,Employment.Receipt.INSUFFICIENT);
        check(contract.consecutiveMisses()==1 && contract==contract.settle(1000,ref,Employment.Receipt.INSUFFICIENT),"duplicate payroll receipt");
        contract=contract.settle(300,contract.due(300).orElseThrow().reference(),Employment.Receipt.PAID);
        check(contract.consecutiveMisses()==0 && contract.nextPeriod()==400,"paid resets streak without catch-up fees");
        for (long tick:List.of(400L,500L,600L)) {
            var wage=contract.due(tick).orElseThrow();
            check(wage.employer().equals(OWNER) && wage.bot().equals(bot),"wrong parties");
            contract=contract.settle(tick,wage.reference(),Employment.Receipt.INSUFFICIENT);
        }
        check(contract.scheme()==Employment.Scheme.INDEPENDENT && contract.employer()==null
                && contract.bot().equals(bot) && contract.due(900).isEmpty(),"independent continuity");
        var old=WorkerControl.player(OWNER,new Sim());
        old.retire();
        check(!old.active() && !old.canControl(OWNER) && !old.order(OWNER,job()),"former owner rights after conversion");
    }
    private static void workerMarket() {
        UUID bot=UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID id=UUID.randomUUID(), checkout=UUID.randomUUID();
        var worker=new Employment(OTHER,bot,null,Employment.Scheme.INDEPENDENT,null,0,0);
        var debt=new BotMarket.Hardship(4,money("9"));
        var price=money("15");
        check(BotMarket.voluntaryOffer(id,worker,debt,3,false,price).isEmpty()
                && BotMarket.voluntaryOffer(id,worker,debt,5,true,price).isEmpty(),"no forced or early listing");
        check(BotMarket.voluntaryOffer(id,Employment.buyout(id,bot,OWNER),debt,3,true,price).isEmpty(),"no player-owned listing");
        var list=BotMarket.voluntaryOffer(id,worker,debt,3,true,price).orElseThrow();
        check(list==list.reserve(OWNER,checkout,money("10"),worker,false),"wrong price");
        check(list.cancel(bot).stage()==BotMarket.Stage.CANCELLED && list==list.cancel(OTHER),"withdrawal");
        check(list==list.reserve(OWNER,checkout,price,worker,true),"existing service blocks settlement");
        var pending=list.reserve(OWNER,checkout,price,worker,false);
        check(pending==pending.reserve(OTHER,UUID.randomUUID(),price,worker,false),"exclusive checkout");
        var payment=pending.payment().orElseThrow();
        check(payment.payee().equals(bot) && payment.payer().equals(OWNER),"bank recipients");
        check(pending==pending.bankResult(payment.reference(),BotMarket.Receipt.UNKNOWN)
                && pending.finalizeBuyout(worker,false).isEmpty(),"uncertain payment cannot transfer contract");
        var declined=pending.bankResult(payment.reference(),BotMarket.Receipt.NOT_PAID);
        var next=declined.reserve(OWNER,UUID.randomUUID(),price,worker,false);
        check(!next.payment().orElseThrow().reference().equals(payment.reference())
                && next==next.bankResult(payment.reference(),BotMarket.Receipt.PAID),"ignore stale receipt");
        var paid=pending.bankResult(payment.reference(),BotMarket.Receipt.PAID);
        var done=paid.finalizeBuyout(worker,false).orElseThrow();
        check(done.sold().stage()==BotMarket.Stage.SOLD && done.contract().scheme()==Employment.Scheme.BUYOUT
                && done.contract().bot().equals(bot) && done.sold().finalizeBuyout(worker,false).isEmpty(),"paid conversion only once");
    }
    private static void serviceContracts() {
        UUID bot=UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        var worker=new Employment(OTHER,bot,null,Employment.Scheme.INDEPENDENT,null,0,0);
        var contract=ServiceAgreement.offer(UUID.randomUUID(),OWNER,bot,job(),money("2"));
        check(contract==contract.decision(OWNER,worker,true)
                && contract.decision(bot,worker,false).stage()==ServiceAgreement.Stage.DECLINED,"customer cannot force hire");
        contract=contract.decision(bot,worker,true);
        var hold=contract.payment().orElseThrow();
        check(hold.operation()==ServiceAgreement.Operation.RESERVE
                && contract==contract.recordWorkDone(bot,worker,MiningWork.State.COMPLETE),"must fund before work");
        contract=contract.confirmed(hold.reference(),true);
        check(contract==contract.recordWorkDone(OWNER,worker,MiningWork.State.COMPLETE)
                && contract==contract.recordWorkDone(bot,worker,MiningWork.State.BLOCKED),"completion must come from executed work");
        contract=contract.recordWorkDone(bot,worker,MiningWork.State.COMPLETE);
        var pay=contract.payment().orElseThrow();
        check(pay.operation()==ServiceAgreement.Operation.PAY_WORKER
                && contract==contract.confirmed(hold.reference(),true),"can't use escrow receipt to publish payment");
        contract=contract.confirmed(pay.reference(),true);
        check(contract.stage()==ServiceAgreement.Stage.SETTLED && contract.payment().isEmpty()
                && worker.employer()==null && worker.scheme()==Employment.Scheme.INDEPENDENT,"service preserves independence");
    }
    private static void extensionBoundary() {
        var registry=new ResidentApi.Registry(resident -> true);
        var resident=new dev.societybots.core.api.ResidentApi.Identity(OTHER,"Resident",Optional.of(OWNER));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var action=new dev.societybots.core.api.ResidentApi.Action() {
            public boolean mayExecute(dev.societybots.core.api.ResidentApi.Context c,dev.societybots.core.api.ResidentApi.Intent i) {
                return c.resident().id().equals(OTHER) && i.targetRef().equals("home");
            }
            public dev.societybots.core.api.ResidentApi.Result execute(dev.societybots.core.api.ResidentApi.Context c,dev.societybots.core.api.ResidentApi.Intent i) {
                count.incrementAndGet(); return dev.societybots.core.api.ResidentApi.Result.APPLIED;
            }
        };
        registry.register("example","visit",action);
        try { registry.register("shopmod","buy",action); throw new AssertionError("direct buy registered"); }
        catch(IllegalArgumentException expected) { /* required presence */ }
        var stand=new CounterVisit.Point("overworld",0,64,0);
        var store=new CounterVisit.Deployment(UUID.randomUUID(),1,CounterVisit.Service.SHOP,stand,4);
        var visits=new CounterVisit((id,site,task)->new CounterVisit.Observation(stand,store,true,true,true,SESSION));
        registry.registerAtCounter("shopmod","buy",CounterVisit.Service.SHOP,visits,action);
        registry.seal();
        var context=new dev.societybots.core.api.ResidentApi.Context(resident,2);
        registry.dispatch(context,new dev.societybots.core.api.ResidentApi.Intent("example:visit","home",1));
        registry.dispatch(context,new dev.societybots.core.api.ResidentApi.Intent("example:visit","private",2));
        registry.dispatch(context,new dev.societybots.core.api.ResidentApi.Intent("other:visit","home",2));
        check(count.get()==0,"extension must reject stale, forbidden and unknown actions");
        registry.dispatch(context,new dev.societybots.core.api.ResidentApi.Intent("example:visit","home",2));
        check(count.get()==1,"valid extension dispatch");
        var buy=new dev.societybots.core.api.ResidentApi.Intent("shopmod:buy","home",2);
        registry.dispatch(context,buy);
        registry.dispatchAtCounter(context,buy,null,3);
        check(count.get()==1,"live proof needed for all registered counter paths");
        var stamp=visits.atDeployment(OTHER,store.instance(),new CounterVisit.Task(CounterVisit.Service.SHOP,"shopmod:buy","home"),3).orElseThrow();
        registry.dispatchAtCounter(context,buy,stamp,3);
        registry.dispatchAtCounter(context,buy,stamp,3);
        check(count.get()==2,"no execution on second dispatch");
        var low=new dev.societybots.core.api.ResidentApi.Limits(dev.societybots.core.api.ResidentApi.Render.LOW_DETAIL,81,2);
        check(low.allowsPlanning(dev.societybots.core.api.ResidentApi.Activity.ACTIVE,80,1)
                && !low.allowsPlanning(dev.societybots.core.api.ResidentApi.Activity.IDLE,80,1)
                && !low.allowsPlanning(dev.societybots.core.api.ResidentApi.Activity.ACTIVE,82,1),"independent simulation bounds");
    }
    private static void newRequirements() {
        var a=UUID.randomUUID();
        var bot=UUID.randomUUID();
        var owner=UUID.randomUUID();
        var worker=new Employment(a,bot,null,Employment.Scheme.INDEPENDENT,null,0,0);
        var q=new LandRequests.Quote("overworld",Set.of(new LandRequests.Chunk(1,2)),money("10"),"v1");
        var req=LandRequests.create(a,worker,q,100).orElseThrow();
        var point=new CounterVisit.Point("overworld",30,64,12);
        var counter=new CounterVisit.Deployment(UUID.randomUUID(),1,CounterVisit.Service.LAND,point,4);
        var visits=new CounterVisit((resident,site,task)->new CounterVisit.Observation(point,counter,true,true,true,SESSION));
        var proof=visits.atDeployment(bot,counter.instance(),req.counterTask(),1).orElseThrow();
        check(req==req.reserve(worker,q,money("20"),1,visits,proof),"land needs approval");
        check(req==req.review(owner,actor->false,true,2),"non-admin review");
        var approved=req.review(owner,actor->actor.equals(owner),true,2);
        check(approved==approved.reserve(worker,new LandRequests.Quote("overworld",q.chunks(),money("11"),"v2"),money("20"),3,visits,proof),"price change");
        check(approved==approved.reserve(worker,q,money("9"),3,visits,proof),"need own balance");
        check(approved==approved.reserve(worker,q,money("20"),3,visits,null),"approved land still requires attendance");
        var pending=approved.reserve(worker,q,money("20"),3,visits,proof);
        var key=pending.checkoutRef().orElseThrow();
        check(pending==pending.settle(key,BotMarket.Receipt.UNKNOWN)
            && pending.settle(key,BotMarket.Receipt.PAID).stage()==LandRequests.Stage.DONE,"land receipt");
        var props=new PropertyRecords();
        var block=new PropertyRecords.BlockKey("overworld",p(1));
        props.botPlaced(block,new PropertyRecords.Building(bot,"stone",1));
        check(props.knownBotBlock(bot,block,"stone",1) && !props.knownBotBlock(owner,block,"stone",1),"own building only");
        props.changed(block);
        check(!props.knownBotBlock(bot,block,"stone",1),"same material replacement invalidates ownership");
        check(!new PropertyRecords.Cargo(bot,Optional.of(a),1).belongsTo(bot)
                && new PropertyRecords.Cargo(bot,Optional.empty(),1).belongsTo(bot),"contract custody separated");
        check(Mentions.namedBots("hello @rEs_Bot, @res_bot @missing",Set.of("Res_Bot")).equals(Set.of("Res_Bot"))
                && Mentions.namedBots("hello res_bot or aa@Res_Bot",Set.of("Res_Bot")).isEmpty(),"ordinary mentions");
    }
    private static void structuresAndPlans() {
        var furnace=new StructureScan.Cell(2,50,0);
        var hopper=furnace.move(0,1,0);
        var other=furnace.move(-2,0,0);
        var rail=furnace.move(0,0,2);
        var ice=furnace.move(0,0,-2);
        var dock=furnace.move(0,0,-3);
        var map=new HashMap<StructureScan.Cell,StructureScan.Block>();
        map.put(furnace,new StructureScan.Block(StructureScan.Part.FURNACE,null));
        map.put(hopper,new StructureScan.Block(StructureScan.Part.HOPPER,furnace));
        map.put(other,new StructureScan.Block(StructureScan.Part.CONTAINER,null));
        map.put(other.move(0,1,0),new StructureScan.Block(StructureScan.Part.HOPPER,furnace));
        map.put(rail,new StructureScan.Block(StructureScan.Part.POWERED_RAIL,null));
        map.put(rail.move(1,1,0),new StructureScan.Block(StructureScan.Part.BUTTON,null));
        map.put(dock,new StructureScan.Block(StructureScan.Part.DOCK,null));
        map.put(dock.move(-1,0,0),new StructureScan.Block(StructureScan.Part.DOCK,null));
        var open=new StructureScan.Block(StructureScan.Part.OPEN,null);
        map.put(dock.move(0,0,-1),new StructureScan.Block(StructureScan.Part.WATER,null));
        map.put(dock.move(-1,0,-1),new StructureScan.Block(StructureScan.Part.WATER,null));
        map.put(dock.move(0,1,-1),open);
        for(int n=1;n<=2;n++){
            map.put(dock.move(0,n,0),open);
            map.put(dock.move(-1,n,0),open);
        }
        map.put(ice,new StructureScan.Block(StructureScan.Part.ICE,null));
        map.put(ice.move(0,0,1),new StructureScan.Block(StructureScan.Part.ICE,null));
        map.put(ice.move(1,1,0),new StructureScan.Block(StructureScan.Part.GUARD,null));
        map.put(ice.move(-1,1,0),new StructureScan.Block(StructureScan.Part.GUARD,null));
        var hits=StructureScan.near(map::get,furnace);
        check(hits.contains(new StructureScan.Match(furnace,StructureScan.Kind.FURNACE_FEED)), "hopper pointing in");
        check(hits.stream().noneMatch(h->h.cell().equals(other)),"hopper pointing away");
        check(hits.contains(new StructureScan.Match(rail,StructureScan.Kind.POSSIBLE_STATION)),"station signal");
        check(hits.contains(new StructureScan.Match(dock,StructureScan.Kind.POSSIBLE_DOCK)),"dock signal");
        check(hits.contains(new StructureScan.Match(ice,StructureScan.Kind.POSSIBLE_ICE_ENTRY)),"guarded ice entry");
        var iceZone=new TransitTolls.Zone("ice","over",ice.x()+0.5,ice.y(),ice.z()+0.5,1d,null,1,3);
        check(TransitTolls.classify("over",furnace,List.of(iceZone),hits).get(0).type()==TransitTolls.Type.STATION_CANDIDATE,
                "dock in the ice boundary may support boarding");
        map.put(ice.move(0,1,0),open);
        check(StructureScan.near(map::get,furnace).contains(new StructureScan.Match(ice,StructureScan.Kind.ICE_BOARDING_CANDIDATE)),
                "ice entry with clear shared platform");
        var railZone=new TransitTolls.Zone("rail","over",rail.x()+0.5,rail.y(),rail.z()+0.5,1d,1d,1,3);
        var dockZone=new TransitTolls.Zone("dock","over",dock.x()+0.5,dock.y(),dock.z()+0.5,1d,null,1,3);
        var wrongLevel=new TransitTolls.Zone("above","over",rail.x()+0.5,rail.y()+3,rail.z()+0.5,1d,1d,1,3);
        var wrongWorld=new TransitTolls.Zone("other","end",rail.x()+0.5,rail.y(),rail.z()+0.5,2,null,1,3);
        var tolls=List.of(railZone,dockZone,wrongLevel,wrongWorld);
        var classified=TransitTolls.classify("over",furnace,tolls,hits);
        check(classified.size()==3 && classified.get(0).type()==TransitTolls.Type.TOLL_ONLY,"rail and button is not enough");
        check(classified.get(1).type()==TransitTolls.Type.STATION_CANDIDATE,"boardable dock inside zone");
        check(classified.get(2).type()==TransitTolls.Type.TOLL_ONLY,"no association across floors");
        check(!dockZone.contains(dock.x()+1.5,dock.y(),dock.z()+1.5),"round shape is not a rectangle");
        check(railZone.contains(rail.x()+1.5,rail.y()-1,rail.z()+1.5),"exact rect boundary");
        check(!railZone.contains(rail.x()+0.5,rail.y()-1.01,rail.z()+0.5),"asymmetric lower edge");
        check(railZone.contains(rail.x()+0.5,rail.y()+3,rail.z()+0.5),"asymmetric upper edge");
        var longGate=new TransitTolls.Zone("long","over",furnace.x()+0.5,furnace.y(),furnace.z()+30.5,1d,35d,1,3);
        check(longGate.intersectsScan(furnace),"long box with center outside scanner");
        map.put(rail.move(0,0,1),new StructureScan.Block(StructureScan.Part.RAIL,null));
        for(int t=0;t<=1;t++){
            map.put(rail.move(-1,0,t),new StructureScan.Block(StructureScan.Part.PLATFORM,null));
            map.put(rail.move(-1,1,t),open);
            map.put(rail.move(-1,2,t),open);
        }
        hits=StructureScan.near(map::get,furnace);
        check(TransitTolls.classify("over",furnace,tolls,hits).get(0).type()==TransitTolls.Type.STATION_CANDIDATE,"platform and rail plus controls");
        map.remove(rail.move(-1,2,0));
        check(TransitTolls.classify("over",furnace,tolls,StructureScan.near(map::get,furnace)).get(0).type()==TransitTolls.Type.TOLL_ONLY,"missing headroom evidence no boarding");
        map.remove(hopper);
        check(StructureScan.near(map::get,furnace).stream().noneMatch(h->h.cell().equals(furnace)),"missing area treated as unknown");
        var order=new PlanGate.Observation(2,false,true,true);
        var ticket=new PlanGate.Ticket(order,100,10);
        check(ticket.accept("UNLOAD",order,109).orElseThrow()==PlanGate.Goal.UNLOAD,"accepted valid fresh plan");
        check(ticket.accept("MINE",order,109).isEmpty(),"unsafe plan");
        check(ticket.accept("/op model",order,109).isEmpty(),"command not a goal");
        check(ticket.accept("UNLOAD",new PlanGate.Observation(3,false,true,true),109).isEmpty(),"replaced snapshot");
        check(ticket.accept("UNLOAD",new PlanGate.Observation(2,false,true,false),109).isEmpty(),"changed permission");
        check(ticket.accept("UNLOAD",order,110).isEmpty(),"stale plan");
        var live=new PlanGate.Observation(2,false,true,true,Optional.of(SESSION));
        var liveTicket=new PlanGate.Ticket(live,100,10);
        check(liveTicket.acceptForExecution("UNLOAD",live,109).isPresent(),"live survival observation");
        check(ticket.acceptForExecution("UNLOAD",order,109).isEmpty(),"simulated plan may not execute");
        var newLife=new PlanGate.Observation(2,false,true,true,Optional.of(new ResidentAuthority.Stamp(UUID.randomUUID(),0)));
        check(liveTicket.acceptForExecution("UNLOAD",newLife,109).isEmpty(),"old plan cannot execute after respawn or a new session");
        check(order.fallback()==PlanGate.Goal.UNLOAD,"unload fallback");
        check(ResidentTraits.forId(OWNER).equals(ResidentTraits.forId(OWNER)),"stable identity traits");
        check(!ResidentTraits.forId(OWNER).equals(ResidentTraits.forId(OTHER)),"distinct residents");
        var industrious=new ResidentTraits(90,30,45,50,45);
        var cautious=new ResidentTraits(30,90,45,50,45);
        var both=new PlanGate.Observation(2,true,true,true);
        check(industrious.prefer(both)==PlanGate.Goal.MINE && cautious.prefer(both)==PlanGate.Goal.UNLOAD,"different preferences when both are allowed");
        check(industrious.prefer(new PlanGate.Observation(3,false,true,false))==PlanGate.Goal.STOP,"trait cannot relax safety");
        check(new PlanGate.Observation(3,true,true,false).fallback()==PlanGate.Goal.STOP,"full storage must stop");
    }
    private static final ResidentAuthority.Stamp SESSION=new ResidentAuthority.Stamp(
            UUID.fromString("12345678-0000-0000-0000-000000000000"),0);
    private static void physicalCounter() {
        var pos=new CounterVisit.Point("overworld",0,64,0);
        var id=UUID.randomUUID();
        var deployment=new CounterVisit.Deployment(id,7,CounterVisit.Service.BANK,pos,4);
        var task=new CounterVisit.Task(CounterVisit.Service.BANK,"deposit","ref-123");
        var state=new CounterVisit.Observation[]{new CounterVisit.Observation(pos,deployment,true,true,true,SESSION)};
        var gate=new CounterVisit((bot,at,intent)->state[0]);
        var proof=gate.atDeployment(OWNER,id,task,10).orElseThrow();
        check(!gate.consume(proof,OTHER,task,10),"no delegate or owner substitution");
        check(!gate.consume(proof,OWNER,new CounterVisit.Task(CounterVisit.Service.BANK,"deposit","ref-456"),11),"no quote swap");
        for(var changed:List.of(
                new CounterVisit.Observation(pos,deployment,true,true,false,SESSION),
                new CounterVisit.Observation(new CounterVisit.Point("nether",0,64,0),deployment,true,true,true,SESSION),
                new CounterVisit.Observation(new CounterVisit.Point("overworld",0,69,0),deployment,true,true,true,SESSION),
                new CounterVisit.Observation(pos,new CounterVisit.Deployment(id,8,CounterVisit.Service.BANK,pos,4),true,true,true,SESSION))) {
            state[0]=changed;
            check(!gate.consume(proof,OWNER,task,12),"independently reject each changed world condition");
            state[0]=new CounterVisit.Observation(pos,deployment,true,true,true,SESSION);
            check(!gate.consume(proof,OWNER,task,12),"a blocked confirmation cannot come back to life");
            proof=gate.atDeployment(OWNER,id,task,10).orElseThrow();
        }
        var expired=gate.atDeployment(OWNER,id,task,10).orElseThrow();
        check(!gate.consume(expired,OWNER,task,51)&&!gate.consume(expired,OWNER,task,12),"expired proof is closed");
        var old=gate.atDeployment(OWNER,id,task,12).orElseThrow();
        state[0]=new CounterVisit.Observation(pos,deployment,true,true,true,new ResidentAuthority.Stamp(SESSION.session(),1));
        check(!gate.consume(old,OWNER,task,12),"old proof after a change of survival authority");
        var current=gate.atDeployment(OWNER,id,task,12).orElseThrow();
        check(gate.consume(current,OWNER,task,12)&&!gate.consume(current,OWNER,task,12),"single use");
    }
    private static void spawnPolicy() {
        var defaultSite=new SpawnPolicy.Position("minecraft:overworld",0,65,0);
        var chosen=new SpawnPolicy.Position("minecraft:overworld",12,64,40);
        var safe=new SpawnPolicy.Safety(true,true,true,true,false,true,true);
        var seen=new ArrayList<SpawnPolicy.Position>();
        SpawnPolicy.World world=at->{seen.add(at);return at.equals(chosen)?safe:null;};
        check(SpawnPolicy.firstJoin(Optional.of(chosen),Optional.of(defaultSite),world).orElseThrow().equals(chosen)
                && seen.equals(List.of(chosen)),"the configured point is the only inspected option");
        check(SpawnPolicy.firstJoin(Optional.of(defaultSite),Optional.of(chosen),world).isEmpty(),"invalid override must not fall back");
        for (int bit=0;bit<7;bit++) {
            boolean[] flags={true,true,true,true,false,true,true};
            flags[bit]=!flags[bit];
            var unsafe=new SpawnPolicy.Safety(flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],flags[6]);
            check(SpawnPolicy.firstJoin(Optional.empty(),Optional.of(defaultSite),at->unsafe).isEmpty(),
                    "unsafe default spawn, single failing safety check "+bit);
            seen.clear();
            check(SpawnPolicy.firstJoin(Optional.of(chosen),Optional.of(defaultSite),at->{
                seen.add(at);
                return at.equals(chosen)?unsafe:safe;
            }).isEmpty() && seen.equals(List.of(chosen)),"unsafe override may not inspect or use fallback "+bit);
        }
        check(SpawnPolicy.firstJoin(Optional.empty(),Optional.empty(),at->{
            throw new AssertionError("no world inspection without a spawn");
        }).isEmpty(),"no spawn available");
        check(SpawnPolicy.firstJoin(Optional.empty(),Optional.of(chosen),world).orElseThrow().equals(chosen),"safe default");
    }
    private static void firstJoinOrchestration() {
        var expected=new FirstJoin.Identity(OTHER,"TestResident");
        var birth=new SpawnPolicy.Position("minecraft:overworld",10,64,10);
        var otherPoint=new SpawnPolicy.Position("minecraft:the_nether",10,64,10);
        class Spawn implements FirstJoin.Port {
            int creations,activations,discards;
            boolean allowed=true, safeBefore=true,safeAfter=true,uncertain,activate=true;
            FirstJoin.Started result=new FirstJoin.Started(expected,UUID.randomUUID(),birth,true,true,false,false,false);
            public boolean canJoin(FirstJoin.Identity bot){return bot.equals(expected) && allowed;}
            public SpawnPolicy.Safety inspect(FirstJoin.Identity bot,SpawnPolicy.Position point) {
                check(bot.equals(expected),"preflight never borrows admin permissions");
                return new SpawnPolicy.Safety(true,true,creations==0?safeBefore:safeAfter,true,false,true,true);
            }
            public Optional<FirstJoin.Started> createSurvival(FirstJoin.Identity bot,SpawnPolicy.Position point) {
                check(bot.equals(expected)&& point.equals(birth),"creation must use pinned identity and location");
                creations++;
                if(uncertain) throw new IllegalStateException("partial join");
                return Optional.of(result);
            }
            public boolean activateIfCurrent(FirstJoin.Started started){check(started==result,"pin exact session");activations++;return activate;}
            public boolean discardUnactivated(FirstJoin.Started started){check(started==result,"do not remove other sessions");discards++;return true;}
            FirstJoin.Result run(){return FirstJoin.start(expected,Optional.of(birth),Optional.of(otherPoint),this);}
            FirstJoin.Result defaultRun(){return FirstJoin.start(expected,Optional.empty(),Optional.of(birth),this);}
        }
        var notAllowed=new Spawn();notAllowed.allowed=false;
        check(notAllowed.run()==FirstJoin.Result.NOT_ALLOWED && notAllowed.creations==0,"no factory call for profile collision or ban");
        var unsafe=new Spawn();unsafe.safeBefore=false;
        check(unsafe.run()==FirstJoin.Result.UNSAFE && unsafe.creations==0,"no spawn or fallback if preflight fails");
        var emptyDefault=new Spawn();emptyDefault.safeBefore=false;
        check(emptyDefault.defaultRun()==FirstJoin.Result.UNSAFE && emptyDefault.creations==0
                && emptyDefault.activations==0 && emptyDefault.discards==0,"void default never creates or activates an actor");
        var wrongMode=new Spawn();
        wrongMode.result=new FirstJoin.Started(expected,UUID.randomUUID(),birth,true,false,false,false,false);
        check(wrongMode.run()==FirstJoin.Result.FAILED && wrongMode.activations==0 && wrongMode.discards==1,"join callback is not activation");
        var foreign=new Spawn();
        foreign.result=new FirstJoin.Started(new FirstJoin.Identity(OWNER,"AnotherPlayer"),UUID.randomUUID(),birth,true,true,false,false,false);
        check(foreign.run()==FirstJoin.Result.RECONCILE && foreign.activations==0 && foreign.discards==0,"do not remove or activate other identity");
        var wrongPosition=new Spawn();
        wrongPosition.result=new FirstJoin.Started(expected,UUID.randomUUID(),otherPoint,true,true,false,false,false);
        check(wrongPosition.run()==FirstJoin.Result.FAILED && wrongPosition.activations==0,"verify exact dimension and coordinates");
        var changed=new Spawn();changed.safeAfter=false;
        check(changed.run()==FirstJoin.Result.FAILED && changed.activations==0 && changed.discards==1,"recheck after factory callbacks");
        var noReply=new Spawn();noReply.uncertain=true;
        check(noReply.run()==FirstJoin.Result.RECONCILE && noReply.creations==1 && noReply.activations==0,"do not retry ambiguous creation");
        var good=new Spawn();
        check(good.run()==FirstJoin.Result.ACTIVE && good.creations==1 && good.activations==1 && good.discards==0,"activate only after all checks");
    }
    private static void ordinaryResidentOnly() {
        check(new ResidentAuthority.Live(true,true,true,true,true,false,false).ordinaryActor(),"ordinary bot");
        for(int bit=0;bit<7;bit++){
            boolean[] args={true,true,true,true,true,false,false};
            args[bit]=!args[bit];
            check(!new ResidentAuthority.Live(args[0],args[1],args[2],args[3],args[4],args[5],args[6]).ordinaryActor(),
                    "disallowed bot state "+bit);
        }
        var reg = new ResidentApi.Registry(resident -> false);
        reg.register("sample","action",new ResidentApi.Action(){
            @Override public boolean mayExecute(ResidentApi.Context c,ResidentApi.Intent i){throw new AssertionError("elevated actor reached provider");}
            @Override public ResidentApi.Result execute(ResidentApi.Context c,ResidentApi.Intent i){throw new AssertionError("elevated actor executed");}
        });
        reg.seal();
        var context=new ResidentApi.Context(new ResidentApi.Identity(OTHER,"Other",Optional.of(OWNER)),10);
        check(reg.dispatch(context,new ResidentApi.Intent("sample:action","target",10))==ResidentApi.Result.REJECTED,
                "cannot bypass the ordinary actor check through an extension");
    }
    private static void arcadeVenueAndGreed() {
        var id=UUID.randomUUID();
        var tags=Set.of("arcadehall_table","arcadehall_id_"+id,"arcadehall_skin_active");
        var cashier=new ArcadeVenue(id,"overworld",10,64,20,"exchange","active");
        var table=new ArcadeVenue(id,"overworld",10,64,20,"roulette","active");
        check(cashier.matches(CounterVisit.Service.ARCADE,"overworld",10,64,20,tags)
                && !cashier.matches(CounterVisit.Service.ARCADE_TABLE,"overworld",10,64,20,tags)
                && !table.matches(CounterVisit.Service.ARCADE,"overworld",10,64,20,tags)
                && table.matches(CounterVisit.Service.ARCADE_TABLE,"overworld",10,64,20,tags),"cashier/table separation");
        check(!table.matches(CounterVisit.Service.ARCADE_TABLE,"nether",10,64,20,tags),"wrong world");
        check(!table.matches(CounterVisit.Service.ARCADE_TABLE,"overworld",11,64,20,tags),"wrong base");
        check(!table.matches(CounterVisit.Service.ARCADE_TABLE,"overworld",10,64,20,
                    Set.of("arcadehall_table","arcadehall_id_"+id,"arcadehall_skin_old")),"rotated or replaced marker");
        check(!table.matches(CounterVisit.Service.ARCADE_TABLE,"overworld",10,64,20,
                    Set.of("arcadehall_table","arcadehall_id_"+id,"arcadehall_id_"+UUID.randomUUID(),"arcadehall_skin_active")),
                "ambiguous marker");
        var pos=new CounterVisit.Point("overworld",10.5,65,20.5);
        var dep=new CounterVisit.Deployment(id,3,CounterVisit.Service.ARCADE,pos,4);
        var buy=new CounterVisit.Task(CounterVisit.Service.ARCADE,"arcadehall:exchange","quote-3");
        var state=new CounterVisit.Observation[]{new CounterVisit.Observation(pos,dep,true,true,true,SESSION)};
        var guard=new CounterVisit((bot,site,task)->state[0]);
        var proof=guard.atDeployment(OWNER,id,buy,40).orElseThrow();
        state[0]=new CounterVisit.Observation(new CounterVisit.Point("overworld",15,65,20.5),dep,true,true,true,SESSION);
        check(!guard.consume(proof,OWNER,buy,41),"walking away after quote blocks confirmation");
        state[0]=new CounterVisit.Observation(pos,dep,true,true,true,SESSION);
        proof=guard.atDeployment(OWNER,id,buy,41).orElseThrow();
        state[0]=new CounterVisit.Observation(pos,new CounterVisit.Deployment(id,3,CounterVisit.Service.ARCADE_TABLE,pos,4),true,true,true,SESSION);
        check(!guard.consume(proof,OWNER,buy,41),"table does not authorize exchange");

        var low=new ResidentTraits(40,50,60,70,30,0);
        var mid=new ResidentTraits(40,50,60,70,30,60);
        var high=new ResidentTraits(40,50,60,70,30,100);
        var available=new ArcadePreference.Conditions(true,true,true,true,false,500,400,80,80,15,15);
        check(!ArcadePreference.consider(low,available,0)
                && ArcadePreference.consider(mid,available,59) && !ArcadePreference.consider(mid,available,60)
                && ArcadePreference.consider(high,available,99),"exact greed bounds");
        check(low.tendency()==high.tendency(),"greed does not change primary trait classifier");
        check(!ArcadePreference.consider(high,new ArcadePreference.Conditions(true,true,false,true,false,500,400,80,80,15,15),0),
                "greed cannot override owner authorization");
        check(!ArcadePreference.consider(high,new ArcadePreference.Conditions(true,true,true,true,false,475,400,80,80,15,15),0),
                "greed cannot spend reserves");
        check(!ArcadePreference.consider(high,new ArcadePreference.Conditions(true,true,true,true,false,500,400,79,80,15,15),0),
                "greed cannot exceed session budget");
        check(!ArcadePreference.consider(high,new ArcadePreference.Conditions(true,true,true,true,false,500,400,80,80,14,15),0),
                "greed cannot override cooldown");
        check(!ArcadePreference.consider(high,new ArcadePreference.Conditions(true,true,true,true,true,500,400,80,80,15,15),0),
                "urgent work wins");
    }
    private static void blueprintBoundary() {
        var block=new dev.societybots.core.api.BuildingPlans.Block(new dev.societybots.core.api.BuildingPlans.Cell(0,0,0),"minecraft:oak_planks");
        var ownerDraft=new dev.societybots.core.api.BuildingPlans.Design(OTHER,Optional.of(OWNER),
                dev.societybots.core.api.BuildingPlans.Need.OWNER_REQUEST,List.of(block));
        var shelter=new dev.societybots.core.api.BuildingPlans.Design(OTHER,Optional.empty(),
                dev.societybots.core.api.BuildingPlans.Need.SHELTER,List.of(block));
        check(ownerDraft.supports(Set.of(block.blockId()))&&!shelter.supports(Set.of()),"both residents draft, palette stays scoped");
        try {
            new dev.societybots.core.api.BuildingPlans.Design(OTHER,Optional.empty(),
                    dev.societybots.core.api.BuildingPlans.Need.OWNER_REQUEST,List.of(block));
            throw new AssertionError("unowned owner request");
        } catch(IllegalArgumentException expected) { /* no authorizing owner */ }
    }
    private static void localModel() {
        var disabled=LocalModel.from(Map.of("SOCIETYBOTS_OLLAMA_PUBLIC_CONTEXT","1"));
        check(!disabled.chat() && !disabled.plan() && !disabled.publicContext(),"local model opt-in");
        var defaultOn=LocalModel.from(Map.of("SOCIETYBOTS_OLLAMA","1"));
        check(defaultOn.chat() && defaultOn.plan() && !defaultOn.publicContext()
                && defaultOn.tag().equals("qwen3:4b") && defaultOn.port()==11434,"default is local and mention-only");
        var custom=LocalModel.from(Map.of("SOCIETYBOTS_OLLAMA","1","SOCIETYBOTS_OLLAMA_MODEL","qwen2.5:3b",
                "SOCIETYBOTS_OLLAMA_PORT","11435","SOCIETYBOTS_OLLAMA_PLAN","0","SOCIETYBOTS_OLLAMA_PUBLIC_CONTEXT","1"));
        check(custom.chat() && !custom.plan() && custom.publicContext() && custom.tag().equals("qwen2.5:3b")
                && custom.port()==11435,"separate chat and planning settings");
        var onlyPlan=LocalModel.from(Map.of("SOCIETYBOTS_OLLAMA","1","SOCIETYBOTS_OLLAMA_CHAT","0",
                "SOCIETYBOTS_OLLAMA_PUBLIC_CONTEXT","1"));
        check(!onlyPlan.chat() && onlyPlan.plan() && !onlyPlan.publicContext(),"no context without chat");
        for(var tag:List.of("","https://external","remote/model:latest","some:cloud","model-large-cloud","a b")){
            try { new LocalModel(true,true,false,tag,11434); throw new AssertionError("bad model tag accepted"); }
            catch(IllegalArgumentException expected) { /* invalid setting */ }
        }
        for(var port:List.of("0","65536","11434/api","-1")){
            try { LocalModel.from(Map.of("SOCIETYBOTS_OLLAMA","1","SOCIETYBOTS_OLLAMA_PORT",port));
                throw new AssertionError("bad model port accepted"); }
            catch(IllegalArgumentException expected) { /* invalid setting */ }
        }
        check(LocalModel.proposal(" { \"goal\" : \"UNLOAD\" } ").orElseThrow()==PlanGate.Goal.UNLOAD,"valid proposal");
        for(var body:List.of("MINE","{goal:'MINE'}","{\"goal\":1}","{\"goal\":\"OP\"}",
                "{\"goal\":\"STOP\",\"goal\":\"MINE\"}","{\"goal\":\"MINE\",\"command\":\"/op\",\"x\":1}",
                "{\"goal\":\"MINE\"} trailing text"," ".repeat(1025)+"{\"goal\":\"STOP\"}")){
            check(LocalModel.proposal(body).isEmpty(),"reject malformed or extraneous output");
        }
        var restricted=new PlanGate.Observation(5,false,true,false);
        var ticket=new PlanGate.Ticket(restricted,100,20);
        var wireGoal=LocalModel.proposal("{\"goal\":\"MINE\"}").orElseThrow();
        check(ticket.accept(wireGoal.name(),restricted,110).isEmpty(),"well formed output cannot widen abilities");
    }
    public static void main(String[] args) {
        localModel();
        physicalCounter();
        blueprintBoundary();
        arcadeVenueAndGreed();
        ordinaryResidentOnly();
        spawnPolicy();
        firstJoinOrchestration();
        structuresAndPlans();
        newRequirements();
        extensionBoundary();
        serviceContracts();
        payroll();
        workerMarket();
        controlModes();
        trade();
        route(true,true,40,Routes.State.FOUND);
        route(false,true,40,Routes.State.NO_KNOWN_ROUTE);
        route(true,false,40,Routes.State.NO_KNOWN_ROUTE);
        route(true,true,2,Routes.State.BUDGET_EXHAUSTED);
        inferredTransport(true,Routes.State.FOUND);
        inferredTransport(false,Routes.State.NO_KNOWN_ROUTE);
        blocked(target(1,Material.DIAMOND_ORE,Origin.PLACED),true,false);
        blocked(target(1,Material.DIAMOND_ORE,Origin.UNKNOWN),true,false);
        blocked(target(6,Material.DIAMOND_ORE,Origin.VERIFIED_NATURAL),true,false);
        blocked(target(1,Material.DIAMOND_ORE,Origin.VERIFIED_NATURAL),false,false);
        blocked(target(1,Material.DIAMOND_ORE,Origin.VERIFIED_NATURAL),true,true);
        var sim = new Sim();
        sim.pending.add(target(1,Material.DIAMOND_ORE,Origin.VERIFIED_NATURAL));
        sim.pending.add(target(2,Material.COPPER_ORE,Origin.VERIFIED_NATURAL));
        var work = new MiningWork(OWNER,sim);
        check(!work.start(OTHER,job()) && work.start(OWNER,job()),"ownership");
        check(!work.stop(OTHER) && !work.pause(OTHER) && work.pause(OWNER),"pause ACL");
        work.tick();
        check(sim.visits==0 && sim.halts==1 && !work.resume(OTHER) && work.resume(OWNER),"resume ACL");
        for (int i=0;i<7;i++) work.tick();
        check(work.state()==MiningWork.State.UNLOAD && sim.storedIngots==0,"wait for actual smelting");
        sim.furnaceReady=true; sim.full=true; work.tick();
        check(work.state()==MiningWork.State.BLOCKED && sim.copperBag==1,"full chest must not discard");
        sim.full=false; sim.container=false;
        check(work.resume(OWNER),"resume blocked");
        work.tick();
        check(work.state()==MiningWork.State.BLOCKED && sim.storedIngots==0,"recheck container permission");
        sim.container=true;
        check(work.resume(OWNER),"resume after permission");
        work.tick(); work.tick();
        check(work.state()==MiningWork.State.COMPLETE && sim.storedIngots==1 && sim.storedDiamonds==1 && work.breaks()==2,"delivery");
        System.out.println("PASS: multimodal route selection, unloaded/forbidden edges and search bound; player and managed authority, five mining guards, furnace wait, full/forbidden storage, exactly-once model delivery; scoped trading, expiry, revocation, spend limit, no uncertain retry; paid/failed/replayed payroll and conversion; voluntary Bot listing and exclusive, receipt-gated bank checkout; independent service contracts; extension permissions, stale-plan checks and simulation bounds; explicit land approvals, property provenance, chat mentions, read-only structure signals, exact toll association, stable traits, arcade cashier/table separation and greed guards, ordinary survival authority, safe spawn choice and post-join activation, single-use service proofs and durable order identity, blueprint boundaries and bounded planner decisions, loopback model configuration and strict proposal format.");
    }
}
