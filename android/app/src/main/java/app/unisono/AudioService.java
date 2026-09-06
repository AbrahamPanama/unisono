package app.unisono;
import android.app.*;
import android.content.*;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

public class AudioService extends Service {
    public static volatile AudioService instance;
    public static volatile String status="Listo para conectar", details="PCM sin compresión · Perfil estable", route="Salida del teléfono";
    public static volatile boolean connected=false,connecting=false;
    public static volatile float volume=0.6f;
    private volatile boolean running;
    private volatile Wire wire;
    private volatile AudioTrack track;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private AudioManager manager;
    private AudioFocusRequest focus;
    private boolean focusHeld, mixWithOtherApps;
    private volatile double syncMs=Double.NaN;
    private volatile int underruns=0;
    private volatile Throwable playbackFailure;
    private volatile boolean sessionActive;
    private long localMinusServer;
    private int rate,delayMs;
    private int reserveMs=500,bitrate=256000;
    private String quality="balanced",wireFormat="float32le";
    private volatile int outputBufferMs=100;
    private volatile float playbackSpeed=1;
    private int primingFrames;
    private boolean forcePCM;
    private long receivedBytes,receivedSince;
    private final Handler main=new Handler(Looper.getMainLooper());
    private ArrayBlockingQueue<Chunk> queue;
    private static class Chunk { long pts,index; int frames; float[] samples; }
    @Override public void onCreate() {
        super.onCreate(); instance=this;
        NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("audio","Audio compartido",NotificationManager.IMPORTANCE_LOW));
        manager=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(change->{ if(!mixWithOtherApps && (change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) { status="Audio interrumpido por otra aplicación"; stopSelf(); } },main).build();
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop=new Intent(this,AudioService.class).setAction("STOP"); PendingIntent end=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"audio").setContentTitle("Unísono").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"Desconectar",end).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||"STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(running) return START_NOT_STICKY;
        startForeground(1,notification("Conectando con tu Mac…"));
        mixWithOtherApps=getSharedPreferences("playback",MODE_PRIVATE).getBoolean("mixWithOtherApps",true);
        // User-controlled mixing does not claim focus. Ignoring loss after claiming GAIN is not
        // enough on Android 12+, which can enforce a fade on the losing player.
        if(!mixWithOtherApps) {
            focusHeld=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if(!focusHeld) { status="No se pudo obtener la salida de audio"; stopSelf(); return START_NOT_STICKY; }
        }
        if(communicationActive()) { status="Conecta después de la llamada"; stopSelf(); return START_NOT_STICKY; }
        quality=getSharedPreferences("playback",MODE_PRIVATE).getString("quality","balanced");
        forcePCM=false; reserveMs="stable".equals(quality) ? 750 : 500; bitrate="stable".equals(quality) ? 160000 : 256000;
        running=true; connecting=true;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Unisono:audio"); wake.acquire();
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE); if(wm!=null) { wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Unisono:wifi"); wifi.acquire(); }
        String link=intent.getStringExtra("link"); worker=new Thread(()->runConnection(link),"Unisono-network"); worker.start(); return START_NOT_STICKY;
    }
    private void runConnection(String link) {
        int retries=0;
        while(running && retries<3) {
            Thread audio=null; AacDecoder decoder=null;
            try {
                playbackFailure=null; status=retries==0 ? "Conectando…" : "Reconectando…"; wire=new Wire(link);
                if(!running) break;
                long best=Long.MAX_VALUE;
                for(int i=0;i<8;i++) { long t0=System.nanoTime(); wire.send(3,Wire.longBytes(t0)); byte[] b=wire.read(); long t1=System.nanoTime(); if(b.length!=17||b[0]!=4) throw new IOException("Sincronización de reloj inválida"); ByteBuffer p=ByteBuffer.wrap(b); p.get(); long echo=p.getLong(),server=p.getLong(); if(echo!=t0) throw new IOException("Respuesta de reloj inválida"); if(t1-t0<best) { best=t1-t0; localMinusServer=t0+(t1-t0)/2-server; } }
                JSONObject request=new JSONObject().put("codec",(forcePCM||"lossless".equals(quality)) ? "pcm" : "aac-lc").put("bitrate",bitrate).put("reserveMs",reserveMs);
                wire.send(5,request.toString().getBytes(StandardCharsets.UTF_8)); byte[] config=wire.read(); if(config[0]!=1) throw new IOException("No llegó el formato de audio");
                JSONObject c=new JSONObject(new String(config,1,config.length-1,StandardCharsets.UTF_8)); rate=c.getInt("rate"); delayMs=c.getInt("delayMs");
                wireFormat=c.getString("format"); primingFrames=c.optInt("primingFrames",0);
                if(rate<8000||rate>192000||c.getInt("channels")!=2||(!"float32le".equals(wireFormat)&&!"aac-lc".equals(wireFormat))||delayMs<100||delayMs>1000) throw new IOException("Formato no compatible");
                volume=(float)c.optDouble("volume",volume); queue=new ArrayBlockingQueue<>(256); playbackFailure=null; sessionActive=true; syncMs=Double.NaN; underruns=0; outputBufferMs=100; playbackSpeed=1;
                if("aac-lc".equals(wireFormat)) {
                    bitrate=c.getInt("bitrate");
                    try { decoder=new AacDecoder(rate,primingFrames,(pts,index,samples)-> { Chunk chunk=new Chunk(); chunk.pts=pts; chunk.index=index; chunk.frames=samples.length/2; chunk.samples=samples; enqueue(chunk); }); }
                    catch(Exception e) { forcePCM=true; throw new IOException("AAC no disponible; probando PCM",e); }
                }
                receivedBytes=0;receivedSince=System.nanoTime();
                audio=new Thread(()->playAudio(),"Unisono-playback"); audio.start();
                connected=true; connecting=false; status="Escuchando tu Mac"; updateDetails();
                main.post(()->getSystemService(NotificationManager.class).notify(1,notification("Escuchando tu Mac · "+("aac-lc".equals(wireFormat) ? "AAC" : "PCM sin pérdida"))));
                long report=0, expected=0,pcmIndex=0,pcmOrigin=-1;
                float[] pcmPending=new float[2048]; int pcmFill=0;
                while(running && playbackFailure==null) {
                    byte[] b=wire.read(); receivedBytes+=b.length; int type=b[0]&255;
                    if(type==2 && decoder==null) {
                        if(b.length<21) throw new IOException("Cabecera de audio incompleta"); ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN); p.get(); Chunk chunk=new Chunk(); chunk.pts=p.getLong(); chunk.index=p.getLong(); chunk.frames=p.getInt();
                        if(chunk.frames<=0||chunk.frames>4096||b.length!=21+chunk.frames*8||chunk.index!=expected) throw new IOException("Secuencia PCM no válida"); expected+=chunk.frames;
                        if(pcmOrigin<0) pcmOrigin=chunk.pts;
                        FloatBuffer samples=p.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                        // Coalesce tiny capture packets so reserve capacity is measured in
                        // useful audio time rather than exhausting a queue with tiny blocks.
                        while(samples.hasRemaining()) {
                            int n=Math.min(samples.remaining(),pcmPending.length-pcmFill);samples.get(pcmPending,pcmFill,n);pcmFill+=n;
                            if(pcmFill==pcmPending.length) {
                                Chunk full=new Chunk(); full.pts=pcmOrigin+pcmIndex*1_000_000_000/rate; full.index=pcmIndex; full.frames=1024; full.samples=pcmPending;enqueue(full);
                                pcmIndex+=1024;pcmPending=new float[2048];pcmFill=0;
                            }
                        }
                    } else if(type==10 && decoder!=null) {
                        if(b.length<=21||b.length>8192) throw new IOException("Paquete AAC no válido");
                        ByteBuffer header=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN); header.get(); long pts=header.getLong(),index=header.getLong(); int frames=header.getInt();
                        if(index!=expected||frames!=1024) throw new IOException("Secuencia AAC no válida");expected+=frames;
                        decoder.packet(pts,index,java.util.Arrays.copyOfRange(b,21,b.length));
                    } else if(type==2||type==10) { throw new IOException("El códec no coincide con el formato anunciado");
                    } else if(type==7) { running=false; status=new String(b,1,b.length-1,StandardCharsets.UTF_8); main.post(this::stopSelf); break; }
                    else if(type==6 && b.length==5) { volume=Math.max(0,Math.min(1,ByteBuffer.wrap(b,1,4).getFloat()));  }
                    long now=System.nanoTime();
                    if(now-report>1_000_000_000L) {
                        JSONObject stats=new JSONObject().put("device",Build.MODEL.startsWith("SM-S938") ? "Galaxy S25 Ultra" : Build.MODEL).put("underruns",underruns).put("bufferMs",outputBufferMs).put("reserveMs",delayMs).put("codec",wireFormat).put("kbps",receivedBytes*8_000_000.0/Math.max(1,System.nanoTime()-receivedSince));
                        if(!Double.isNaN(syncMs)) stats.put("syncMs",syncMs);
                        wire.send(8,stats.toString().getBytes(StandardCharsets.UTF_8)); report=now;
                    }
                }
                if(playbackFailure!=null) throw new IOException(playbackFailure.getMessage(),playbackFailure);
            } catch(Exception e) { if(running) {
                    Throwable cause=playbackFailure!=null ? playbackFailure : e;
                    String reason=(cause.getMessage()==null ? cause.getClass().getSimpleName() : cause.getMessage()).replaceAll("unisono://\\S+","[enlace privado]");
                    status="Conexión interrumpida: "+reason;
                    android.util.Log.w("UnisonoAudio","Recovery: "+reason+"; underruns="+underruns+"; syncMs="+syncMs);
                    retries++;
                    reserveMs=PlaybackTuning.nextReserve(Math.max(reserveMs,delayMs));
                    if(!"lossless".equals(quality)) bitrate=160000;
                } }
            finally { if(decoder!=null) decoder.close(); sessionActive=false; connected=false; Wire w=wire; wire=null; if(w!=null) w.close(); if(audio!=null) { audio.interrupt(); try { audio.join(1500); } catch(InterruptedException ignored) {} } }
            if(running && retries<3) { connecting=true; try { Thread.sleep(1000); } catch(InterruptedException ignored) {} }
        }
        connecting=false; if(running) main.post(this::stopSelf);
    }
    private void enqueue(Chunk chunk) throws Exception {
        if(!queue.offer(chunk,100,TimeUnit.MILLISECONDS)) throw new IOException("La reproducción se atrasó");
    }
    private void updateDetails() {
        details=("aac-lc".equals(wireFormat) ? "AAC · "+bitrate/1000+" kbps" : "PCM sin pérdida · "+rate+" Hz")+" · Reserva "+delayMs+" ms · Búfer "+outputBufferMs+" ms";
    }
    private void playAudio() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); AudioTrack t=null;
        try {
            PlaybackTuning tuning=new PlaybackTuning(); PlaybackTuning.Gain gain=new PlaybackTuning.Gain();
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT); if(min<=0) throw new IOException("Salida PCM no compatible");
            // Allocate room for recovery, and start with 100 ms rather than a 5 ms prefill.
            // Normal performance mode avoids requesting a fast path while time stretching.
            t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min*2,rate*8/4)).build();
            if(t.getState()!=AudioTrack.STATE_INITIALIZED) throw new IOException("No se pudo abrir la salida de audio");
            int actual=t.setBufferSizeInFrames(Math.max(min/8,rate*tuning.bufferMs()/1000));
            if(actual<=0) throw new IOException("No se pudo preparar el búfer de audio");
            outputBufferMs=actual*1000/rate; updateDetails();
            int prefillFrames=Math.min(actual,rate/10);
            if(Build.VERSION.SDK_INT>=31) t.setStartThresholdInFrames(prefillFrames);
            t.setVolume(1); track=t;
            Chunk first=queue.poll(5,TimeUnit.SECONDS); if(first==null) throw new IOException("La Mac no envió audio. Revisa su permiso de captura.");
            long origin=first.pts+localMinusServer+delayMs*1_000_000L;
            Chunk pending=first; int offset=0,filled=0;
            gain.apply(pending.samples,volume,rate);
            while(running&&sessionActive&&filled<prefillFrames*2) {
                int n=t.write(pending.samples,offset,Math.min(pending.samples.length-offset,prefillFrames*2-filled),AudioTrack.WRITE_NON_BLOCKING);
                if(n<0) throw new IOException("Error preparando audio: "+n);
                if(n==0) throw new IOException("La salida no aceptó la reserva inicial");
                offset+=n; filled+=n;
                if(offset==pending.samples.length && filled<prefillFrames*2) {
                    pending=queue.poll(5,TimeUnit.SECONDS); if(pending==null) throw new IOException("La red no llenó la reserva inicial");
                    gain.apply(pending.samples,volume,rate); offset=0;
                }
            }
            long start=origin-20_000_000L;
            if(System.nanoTime()>origin+50_000_000L) throw new IOException("La reserva inicial fue insuficiente");
            while(running&&sessionActive&&System.nanoTime()<start) { LockSupport.parkNanos(Math.min(2_000_000L,start-System.nanoTime())); if(Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
            if(!running||!sessionActive) return;
            t.play(); write(t,pending.samples,offset); long lastCorrection=0; int routedId=-1;
            while(running&&sessionActive) {
                Chunk c=queue.poll(2,TimeUnit.SECONDS); if(c==null) throw new IOException("La red dejó de entregar audio");
                if(System.nanoTime()>c.pts+localMinusServer+delayMs*1_000_000L+200_000_000L) throw new IOException("La red superó la reserva; aumentando estabilidad");
                gain.apply(c.samples,volume,rate); write(t,c.samples,0); long now=System.nanoTime();
                if(now-lastCorrection>500_000_000L) {
                    if(communicationActive()) { running=false; status="Audio detenido durante una llamada"; Wire w=wire; if(w!=null) w.close(); main.post(this::stopSelf); break; }
                    underruns=t.getUnderrunCount();
                    if(tuning.observeUnderruns(underruns)) {
                        int frames=t.setBufferSizeInFrames(Math.max(min/8,rate*tuning.bufferMs()/1000));
                        if(frames>0) { outputBufferMs=frames*1000/rate; updateDetails(); }
                    }
                    if(underruns>=3) throw new IOException("Varios cortes de salida; aumentando la reserva");
                    AudioTimestamp ts=new AudioTimestamp();
                    if(t.getTimestamp(ts)) {
                        double desired=origin+ts.framePosition*(1e9/rate); syncMs=(ts.nanoTime-desired)/1e6;
                        // A fixed device/route delay is not evidence of a broken stream.
                        // Samsung can report hundreds of ms while playback has zero underruns.
                        // Keep timing diagnostic and slew correction; never reconnect for phase alone.
                        float next=tuning.correction(syncMs,now);
                        if(next!=playbackSpeed) {
                            try { t.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1).setSpeed(next)); playbackSpeed=next; } catch(IllegalArgumentException ignored) {}
                        }
                    }
                    AudioDeviceInfo device=t.getRoutedDevice();
                    if(device!=null) { if(routedId!=-1&&routedId!=device.getId()) throw new IOException("Cambió la salida de audio; resincronizando"); routedId=device.getId(); route=device.getProductName().toString(); }
                    lastCorrection=now;
                }
            }
        } catch(Throwable e) { if(running&&sessionActive) { playbackFailure=e; Wire w=wire; if(w!=null) w.close(); } }
        finally { track=null; if(t!=null) { try { t.pause(); t.flush(); } catch(Exception ignored) {} t.release(); } }
    }
    private boolean communicationActive() {
        int mode=manager.getMode();
        return mode==AudioManager.MODE_IN_CALL || mode==AudioManager.MODE_IN_COMMUNICATION || mode==AudioManager.MODE_RINGTONE;
    }
    private void write(AudioTrack t,float[] data,int offset) throws Exception { while(offset<data.length&&running&&sessionActive) { int n=t.write(data,offset,data.length-offset,AudioTrack.WRITE_BLOCKING); if(n<=0) throw new IOException("Salida de audio no disponible: "+n); offset+=n; } }
    public void setVolume(float v) { volume=Math.max(0,Math.min(1,v));  }
    @Override public void onDestroy() {
        running=false; sessionActive=false; connected=false; connecting=false;
        Wire w=wire; if(w!=null) w.close(); if(worker!=null) worker.interrupt();
        if(wake!=null&&wake.isHeld()) wake.release(); if(wifi!=null&&wifi.isHeld()) wifi.release(); if(focusHeld&&manager!=null&&focus!=null) manager.abandonAudioFocusRequest(focus); focusHeld=false;
        instance=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd,PrintWriter out,String[] args) {
        out.println("UnisonoAudio connected="+connected+" connecting="+connecting+" running="+running);
        out.println("codec="+wireFormat+" reserveMs="+delayMs+" bufferMs="+outputBufferMs+" underruns="+underruns+" syncMs="+syncMs+" speed="+playbackSpeed);
        out.println("status="+status.replaceAll("unisono://\\S+","[enlace privado]"));
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
