import app.unisono.Wire;
import java.nio.*;
import java.nio.charset.StandardCharsets;
public class CaptureTest {
  public static void main(String[] args) throws Exception {
    String key=new String(System.in.readAllBytes(),StandardCharsets.UTF_8).trim();
    try(Wire w=new Wire("unisono://127.0.0.1:45871#"+key)) {
      boolean aac=args.length>0&&args[0].equals("aac");
      w.send(5,aac ? "{\"codec\":\"aac-lc\",\"bitrate\":256000,\"reserveMs\":500}".getBytes(StandardCharsets.UTF_8) : new byte[0]); byte[] config=w.read();
      if(config[0]!=1) throw new AssertionError("Missing config");
      if(aac&&!new String(config,StandardCharsets.UTF_8).contains("aac-lc")) throw new AssertionError("AAC not negotiated");
      System.out.println("Capture config: "+new String(config,1,config.length-1,StandardCharsets.UTF_8));
      long end=System.nanoTime()+8_000_000_000L,frames=0,nonzero=0,packets=0; float peak=0;
      while(System.nanoTime()<end) {
        byte[] b=w.read(); if(b[0]!=(aac ? 10 : 2)) continue; packets++;
        ByteBuffer p=ByteBuffer.wrap(b); p.position(17); int n=p.getInt(); frames+=n;
        p.order(ByteOrder.LITTLE_ENDIAN);
        while(!aac&&p.remaining()>=4) { float f=p.getFloat(); if(f!=0) nonzero++; peak=Math.max(peak,Math.abs(f)); }
        w.send(8,"{\"device\":\"Receptor de prueba local\"}".getBytes(StandardCharsets.UTF_8));
      }
      System.out.println(aac ? "AAC CAPTURE: encoded frames="+frames+", packets="+packets : "PCM CAPTURE: frames="+frames+", nonzero="+nonzero+", peak="+peak);
      if(frames<100000||packets<10) throw new AssertionError("Capture too short");
      if(!aac&&nonzero==0) throw new AssertionError("All samples zero: permission or source audio needs verification");
    }
  }
}
