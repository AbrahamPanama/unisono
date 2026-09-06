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
    private volatile double syncMs=Double.NaN;
    private volatile int underruns=0;
    private volatile Throwable playbackFailure;
    private volatile boolean sessionActive;
    private long localMinusServer;
    private int rate,delayMs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private ArrayBlockingQueue<Chunk> queue;
    private static class Chunk { long pts,index; int frames; float[] samples; }
    @Override public void onCreate() {
        super.onCreate(); instance=this;
        NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("audio","Audio compartido",NotificationManager.IMPORTANCE_LOW));
        manager=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(change->{ if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { status="Audio interrumpido por otra aplicación"; stopSelf(); } },main).build();
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
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status="No se pudo obtener la salida de audio"; stopSelf(); return START_NOT_STICKY; }
        running=true; connecting=true;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Unisono:audio"); wake.acquire();
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE); if(wm!=null) { wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Unisono:wifi"); wifi.acquire(); }
        String link=intent.getStringExtra("link"); worker=new Thread(()->runConnection(link),"Unisono-network"); worker.start(); return START_NOT_STICKY;
    }
    private void runConnection(String link) {
        int retries=0;
        while(running && retries<3) {
            Thread audio=null;
            try {
                status=retries==0 ? "Conectando…" : "Reconectando…"; wire=new Wire(link);
                long best=Long.MAX_VALUE;
                for(int i=0;i<8;i++) { long t0=System.nanoTime(); wire.send(3,Wire.longBytes(t0)); byte[] b=wire.read(); long t1=System.nanoTime(); if(b.length!=17||b[0]!=4) throw new IOException("Sincronización de reloj inválida"); ByteBuffer p=ByteBuffer.wrap(b); p.get(); long echo=p.getLong(),server=p.getLong(); if(echo!=t0) throw new IOException("Respuesta de reloj inválida"); if(t1-t0<best) { best=t1-t0; localMinusServer=t0+(t1-t0)/2-server; } }
                wire.send(5,new byte[0]); byte[] config=wire.read(); if(config[0]!=1) throw new IOException("No llegó el formato de audio");
                JSONObject c=new JSONObject(new String(config,1,config.length-1,StandardCharsets.UTF_8)); rate=c.getInt("rate"); delayMs=c.getInt("delayMs");
                if(rate<8000||rate>192000||c.getInt("channels")!=2||!"float32le".equals(c.getString("format"))||delayMs<100||delayMs>1000) throw new IOException("Formato no compatible");
                volume=(float)c.optDouble("volume",volume); queue=new ArrayBlockingQueue<>(256); playbackFailure=null; sessionActive=true; syncMs=Double.NaN;
                audio=new Thread(()->playAudio(),"Unisono-playback"); audio.start();
                connected=true; connecting=false; status="Escuchando tu Mac"; details="PCM flotante · "+rate+" Hz · Reserva "+delayMs+" ms";
                main.post(()->getSystemService(NotificationManager.class).notify(1,notification("Escuchando tu Mac · PCM sin compresión")));
                long report=0, expected=0;
                while(running && playbackFailure==null) {
                    byte[] b=wire.read(); int type=b[0]&255;
                    if(type==2) {
                        if(b.length<21) throw new IOException("Cabecera de audio incompleta"); ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN); p.get(); Chunk chunk=new Chunk(); chunk.pts=p.getLong(); chunk.index=p.getLong(); chunk.frames=p.getInt();
                        if(chunk.frames<=0||chunk.frames>4096||b.length!=21+chunk.frames*8||chunk.index!=expected) throw new IOException("Secuencia PCM no válida"); expected+=chunk.frames;
                        chunk.samples=new float[chunk.frames*2]; p.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(chunk.samples);
                        if(!queue.offer(chunk,100,TimeUnit.MILLISECONDS)) throw new IOException("La reproducción se atrasó");
                    } else if(type==7) { running=false; status=new String(b,1,b.length-1,StandardCharsets.UTF_8); main.post(this::stopSelf); break; }
                    else if(type==6 && b.length==5) { volume=Math.max(0,Math.min(1,ByteBuffer.wrap(b,1,4).getFloat())); AudioTrack t=track; if(t!=null) t.setVolume(volume); }
                    long now=System.nanoTime();
                    if(now-report>1_000_000_000L) {
                        JSONObject stats=new JSONObject().put("device",Build.MODEL.startsWith("SM-S938") ? "Galaxy S25 Ultra" : Build.MODEL).put("underruns",underruns);
                        if(!Double.isNaN(syncMs)) stats.put("syncMs",syncMs);
                        wire.send(8,stats.toString().getBytes(StandardCharsets.UTF_8)); report=now;
                    }
                }
                if(playbackFailure!=null) throw new IOException(playbackFailure.getMessage(),playbackFailure);
            } catch(Exception e) { if(running) { status="Conexión interrumpida: "+(e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage()); retries++; } }
            finally { sessionActive=false; connected=false; Wire w=wire; wire=null; if(w!=null) w.close(); if(audio!=null) { audio.interrupt(); try { audio.join(1500); } catch(InterruptedException ignored) {} } }
            if(running && retries<3) { connecting=true; try { Thread.sleep(1000); } catch(InterruptedException ignored) {} }
        }
        connecting=false; if(running) main.post(this::stopSelf);
    }
    private void playAudio() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); AudioTrack t=null;
        try {
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT); if(min<=0) throw new IOException("Salida PCM no compatible");
            t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setBufferSizeInBytes(Math.max(min*2,rate*8/10)).build();
            if(t.getState()!=AudioTrack.STATE_INITIALIZED) throw new IOException("No se pudo abrir la salida de audio");
            t.setBufferSizeInFrames(Math.max(min/8,rate/40)); if(Build.VERSION.SDK_INT>=31) t.setStartThresholdInFrames(Math.min(rate/200,t.getBufferSizeInFrames())); t.setVolume(volume); track=t;
            Chunk first=queue.poll(5,TimeUnit.SECONDS); if(first==null) throw new IOException("La Mac no envió audio. Revisa su permiso de captura.");
            long origin=first.pts+localMinusServer+delayMs*1_000_000L;
            // Start with a small prefill. Later AudioTimestamp feedback measures the actual hardware path.
            int prefill=Math.min(first.samples.length,Math.min(rate/200,t.getBufferSizeInFrames())*2);
            int wrote=t.write(first.samples,0,prefill,AudioTrack.WRITE_NON_BLOCKING); if(wrote<0) throw new IOException("Error preparando audio: "+wrote);
            long start=origin-25_000_000L;
            while(running&&sessionActive&&System.nanoTime()<start) { LockSupport.parkNanos(Math.min(2_000_000L,start-System.nanoTime())); if(Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
            t.play(); write(t,first.samples,wrote); long lastCorrection=0; int routedId=-1;
            while(running&&sessionActive) {
                Chunk c=queue.poll(2,TimeUnit.SECONDS); if(c==null) throw new IOException("La red dejó de entregar audio");
                if(System.nanoTime()>c.pts+localMinusServer+delayMs*1_000_000L+200_000_000L) throw new IOException("La red superó la reserva; resincronizando");
                write(t,c.samples,0); long now=System.nanoTime();
                if(now-lastCorrection>500_000_000L) {
                    AudioTimestamp ts=new AudioTimestamp();
                    if(t.getTimestamp(ts)) {
                        double desired=origin+ts.framePosition*(1e9/rate); syncMs=(ts.nanoTime-desired)/1e6;
                        // Correct independent DAC clock drift without dropping source packets.
                        // This changes output samples: network transport is lossless, output is not bit-perfect.
                        float speed=(float)(1+Math.max(-0.005,Math.min(0.005,syncMs/20000.0)));
                        try { t.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1).setSpeed(speed)); } catch(IllegalArgumentException ignored) {}
                    }
                    underruns=t.getUnderrunCount(); AudioDeviceInfo device=t.getRoutedDevice();
                    if(device!=null) { if(routedId!=-1&&routedId!=device.getId()) throw new IOException("Cambió la salida de audio; resincronizando"); routedId=device.getId(); route=device.getProductName().toString(); }
                    lastCorrection=now;
                }
            }
        } catch(Throwable e) { if(running&&sessionActive) { playbackFailure=e; Wire w=wire; if(w!=null) w.close(); } }
        finally { track=null; if(t!=null) { try { t.pause(); t.flush(); } catch(Exception ignored) {} t.release(); } }
    }
    private void write(AudioTrack t,float[] data,int offset) throws Exception { while(offset<data.length&&running&&sessionActive) { int n=t.write(data,offset,data.length-offset,AudioTrack.WRITE_BLOCKING); if(n<=0) throw new IOException("Salida de audio no disponible: "+n); offset+=n; } }
    public void setVolume(float v) { volume=Math.max(0,Math.min(1,v)); AudioTrack t=track; if(t!=null) try { t.setVolume(volume); } catch(IllegalStateException ignored) {} }
    @Override public void onDestroy() {
        running=false; sessionActive=false; connected=false; connecting=false;
        Wire w=wire; if(w!=null) w.close(); if(worker!=null) worker.interrupt();
        if(wake!=null&&wake.isHeld()) wake.release(); if(wifi!=null&&wifi.isHeld()) wifi.release(); if(manager!=null&&focus!=null) manager.abandonAudioFocusRequest(focus);
        instance=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
