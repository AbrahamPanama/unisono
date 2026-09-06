import app.unisono.Wire;
import java.nio.*;
import java.nio.charset.StandardCharsets;
public class CaptureTest {
  public static void main(String[] args) throws Exception {
    String key=new String(System.in.readAllBytes(),StandardCharsets.UTF_8).trim();
    try(Wire w=new Wire("unisono://127.0.0.1:45871#"+key)) {
      w.send(5,new byte[0]); byte[] config=w.read();
      if(config[0]!=1) throw new AssertionError("Missing config");
      System.out.println("Capture config: "+new String(config,1,config.length-1,StandardCharsets.UTF_8));
      long end=System.nanoTime()+15_000_000_000L,frames=0,nonzero=0; float peak=0;
      while(System.nanoTime()<end) {
        byte[] b=w.read(); if(b[0]!=2) continue;
        ByteBuffer p=ByteBuffer.wrap(b); p.position(17); int n=p.getInt(); frames+=n;
        p.order(ByteOrder.LITTLE_ENDIAN);
        while(p.remaining()>=4) { float f=p.getFloat(); if(f!=0) nonzero++; peak=Math.max(peak,Math.abs(f)); }
        w.send(8,"{\"device\":\"Receptor de prueba local\"}".getBytes(StandardCharsets.UTF_8));
      }
      System.out.println("CAPTURE RESULT: frames="+frames+", nonzero="+nonzero+", peak="+peak);
      if(nonzero==0) throw new AssertionError("All samples zero: permission or source audio needs verification");
    }
  }
}
