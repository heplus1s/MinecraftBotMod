package dev.societybots.core;

import java.util.SplittableRandom;
import java.util.UUID;

/** Stable baseline preferences, not abilities or authorization. */
public record ResidentTraits(int industry,int prudence,int exploration,int sociability,int commerce,int greed) {
    public enum Tendency { INDUSTRIAL, CAUTIOUS, EXPLORER, SOCIAL, MERCHANT }
    public ResidentTraits {
        for(int v:new int[]{industry,prudence,exploration,sociability,commerce,greed})
            if(v<0||v>100) throw new IllegalArgumentException("trait");
    }
    public ResidentTraits(int industry,int prudence,int exploration,int sociability,int commerce) {
        this(industry,prudence,exploration,sociability,commerce,50);
    }
    public static ResidentTraits forId(UUID id) {
        var rng=new SplittableRandom(id.getMostSignificantBits()^Long.rotateLeft(id.getLeastSignificantBits(),31));
        return new ResidentTraits(rng.nextInt(20,81),rng.nextInt(20,81),rng.nextInt(20,81),rng.nextInt(20,81),rng.nextInt(20,81),rng.nextInt(0,101));
    }
    public Tendency tendency() {
        int[] scores={industry,prudence,exploration,sociability,commerce};
        int best=0;
        for(int i=1;i<scores.length;i++) if(scores[i]>scores[best]) best=i;
        return Tendency.values()[best];
    }
    public PlanGate.Goal prefer(PlanGate.Observation observation) {
        if(observation.permits(PlanGate.Goal.MINE) && observation.permits(PlanGate.Goal.UNLOAD))
            return industry>prudence?PlanGate.Goal.MINE:PlanGate.Goal.UNLOAD;
        return observation.fallback();
    }
}
