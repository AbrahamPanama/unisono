import app.unisono.Wire;
import java.nio.*;
import java.nio.charset.StandardCharsets;
public class CaptureTest {
  public static void main(String[] args) throws Exception {
    String key=new String(System.in.readAllBytes(),StandardCharsets.UTF_8).trim();
    try(Wire w=new Wire("unisono://127.0.0.1:45871#"+key)) {
      boolean aac=args.length>0&&args[0].equals("aac"),flac=args.length>0&&args[0].equals("flac"),compressed=aac||flac;
      String request="{\"codec\":\""+(flac ? "flac" : "aac-lc")+"\",\"bitrate\":256000,\"reserveMs\":250}";
      w.send(5,compressed ? request.getBytes(StandardCharsets.UTF_8) : new byte[0]); byte[] config=w.read();
      if(config[0]!=1) throw new AssertionError("Missing config");
      if(aac&&!new String(config,StandardCharsets.UTF_8).contains("aac-lc")) throw new AssertionError("AAC not negotiated");
      if(flac&&!new String(config,StandardCharsets.UTF_8).contains("\"flac\"")) throw new AssertionError("FLAC not negotiated");
      System.out.println("Capture config: "+new String(config,1,config.length-1,StandardCharsets.UTF_8));
      long end=System.nanoTime()+8_000_000_000L,frames=0,nonzero=0,packets=0; float peak=0;
      while(System.nanoTime()<end) {
        byte[] b=w.read(); if(b[0]!=(flac ? 11 : aac ? 10 : 2)) throw new AssertionError("Unexpected capture message "+b[0]); packets++;
        ByteBuffer p=ByteBuffer.wrap(b); p.position(9); long index=p.getLong(); int n=p.getInt();
        if(index!=frames||n<=0||(compressed&&n>1024)) throw new AssertionError("Capture frame sequence"); frames+=n;
        p.order(ByteOrder.LITTLE_ENDIAN);
        while(!compressed&&p.remaining()>=4) { float f=p.getFloat(); if(f!=0) nonzero++; peak=Math.max(peak,Math.abs(f)); }
        w.send(8,"{\"device\":\"Receptor de prueba local\"}".getBytes(StandardCharsets.UTF_8));
      }
      System.out.println(compressed ? (flac ? "FLAC" : "AAC")+" CAPTURE: encoded frames="+frames+", packets="+packets : "PCM CAPTURE: frames="+frames+", nonzero="+nonzero+", peak="+peak);
      if(frames<100000||packets<10) throw new AssertionError("Capture too short");
      if(!compressed&&nonzero==0) throw new AssertionError("All samples zero: permission or source audio needs verification");
    }
  }
}
