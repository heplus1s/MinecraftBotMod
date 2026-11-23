package dev.societybots.fabric;
import com.google.gson.JsonObject;
public class TollSnapshotCheck {
  public static void main(String[] args) {
    var snapshot = new JsonObject();
    snapshot.addProperty("success", true);
    snapshot.addProperty("revision", 4);
    snapshot.addProperty("payload", """
      {"schema":1,"points":{
       "z":{"id":"cccccccc-1111-2222-3333-444444444444","dimension":"minecraft:overworld","x":0.5,"y":64,"z":1.5,"radius":3,"halfZ":35,"above":3,"below":1},
       "w":{"id":"dddddddd-1111-2222-3333-444444444444","dimension":"minecraft:the_nether","x":0,"y":65,"z":1,"radius":4,"height":2}
      },"orders":{"not-needed":"test input"},"seizures":{}}
      """);
    var points=BankTollView.decode(snapshot.toString()).orElseThrow();
    if(points.size()!=2 || !points.get(0).contains(3.5,63,36.5)||points.get(0).contains(3.5,62.99,36.5)
       || points.get(1).contains(4,65,5) || points.get(1).above()!=2) throw new AssertionError("geometry");
    snapshot.addProperty("success", false);
    if(BankTollView.decode(snapshot.toString()).isPresent()) throw new AssertionError("gap reported as no toll");
    snapshot.addProperty("success", true);
    snapshot.addProperty("payload", "{\"schema\":1,\"points\":{}}");
    if(!BankTollView.decode(snapshot.toString()).orElseThrow().isEmpty()) throw new AssertionError("empty snapshot");
    if(BankTollView.decode("{}").isPresent()) throw new AssertionError("bad snapshot");
    System.out.println("PASS: serialized authoritative snapshot, round and asymmetric rectangular zones, missing/empty distinction.");
  }
}
