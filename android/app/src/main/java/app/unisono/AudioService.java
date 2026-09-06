package app.unisono;
import android.app.*;
import android.content.*;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.atomic.AtomicLong;
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
    private volatile long syncObservedAt;
    private volatile int underruns=0;
    private volatile Throwable playbackFailure;
    private volatile boolean sessionActive;
    private volatile long localMinusServer;
    private volatile int rate,delayMs;
    private int reserveMs=250,bitrate=256000;
    private String quality="balanced",wireFormat="float32le";
    private volatile int outputBufferMs=100;
    private volatile float playbackSpeed=1;
    private int primingFrames;
    private boolean forcePCM;
    private long receivedBytes,receivedSince;
    private final Object settingsLock=new Object(),underrunLock=new Object();
    private volatile DebugSettings activeDebug=DebugSettings.defaults(),pendingDebug;
    private volatile boolean restartRequested;
    private volatile int requestedBufferMs=100,actualPrefillMs;
    private volatile double packetGapMs=Double.NaN,jitterMs=Double.NaN,decodeMs=Double.NaN,writeMs=Double.NaN,arrivalAgeMs=Double.NaN;
    private volatile double rttMs=Double.NaN,initialRttMs=Double.NaN;
    private volatile String routeType="unknown",lastFailure="",rttSource="unavailable";
    private volatile JSONObject macTelemetry;
    private volatile long macTelemetryAt;
    private final AtomicLong packetGapPeak=new AtomicLong(Long.MIN_VALUE),decodePeak=new AtomicLong(Long.MIN_VALUE),writePeak=new AtomicLong(Long.MIN_VALUE),arrivalAgePeak=new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong queueFrames=new AtomicLong(),samplesWritten=new AtomicLong(),packetCount=new AtomicLong(),byteCount=new AtomicLong();
    private volatile long reconnectCount,totalUnderruns;
    private long previousPacketAt,previousPacketPts,previousReportBytes,previousReportTime,lastHead,headWrap,startedAt,previousCpu,previousCpuAt;
    private volatile long clockPing;
    private volatile int reportedUnderruns;
    private final Runnable diagnosticsTick=new Runnable() { public void run() {
        if(instance!=AudioService.this)return;
        recordDiagnostics();
        main.postDelayed(this,1000);
    }};
    private final Handler main=new Handler(Looper.getMainLooper());
    private ArrayBlockingQueue<Chunk> queue;
    private static class Chunk { long pts,index; int frames; float[] samples; }
    private static final class PlaybackQueueException extends IOException {
        PlaybackQueueException() { super("La reproducción se atrasó"); }
    }
    @Override public void onCreate() {
        super.onCreate(); instance=this; activeDebug=DebugSettings.load(this); startedAt=SystemClock.elapsedRealtime(); main.post(diagnosticsTick);
        NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("audio","Audio compartido",NotificationManager.IMPORTANCE_LOW));
        manager=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(change->{ if(!mixWithOtherApps && (change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) { status="Audio interrumpido por otra aplicación"; stopSelf(); } },main).build();
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop=new Intent(this,AudioService.class).setAction("STOP"); PendingIntent end=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"audio").setContentTitle("Unísono").setContentText(text).setSmallIcon(R.drawable.ic_notification).setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"Desconectar",end).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||"STOP".equals(intent.getAction())) { if(intent!=null)status="Desconectado";stopSelf();return START_NOT_STICKY; }
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
        activeDebug=DebugSettings.load(this); resetRequestedValues(); delayMs=0;
        AudioDiagnostics.event("connect","Nueva conexión solicitada",activeDebug);
        running=true; connecting=true;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Unisono:audio"); wake.acquire();
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE); if(wm!=null) { wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Unisono:wifi"); wifi.acquire(); }
        String link=intent.getStringExtra("link"); worker=new Thread(()->runConnection(link),"Unisono-network"); worker.start(); return START_NOT_STICKY;
    }
    public static void applyDebugSettings(Context context,DebugSettings settings) {
        applyDebugSettings(context,settings,false);
    }
    private static void applyDebugSettings(Context context,DebugSettings settings,boolean remote) {
        Context application=context.getApplicationContext();
        Runnable apply=()->{
            // One serialized transaction keeps persisted controls and the pending restart aligned,
            // regardless of whether the phone UI or the Mac sent the settings.
            settings.save(application);
            AudioDiagnostics.event(remote?"remoteSettings":"settings",remote?"Ajustes enviados desde la Mac":"Ajustes aplicados: "+(settings.manual?"manual":"automático"),settings);
            AudioService service=instance;if(service!=null)service.requestReconfigure(settings);
        };
        if(Looper.myLooper()==Looper.getMainLooper())apply.run();
        else new Handler(Looper.getMainLooper()).post(apply);
    }
    private void requestReconfigure(DebugSettings settings) {
        boolean reconfigure;
        synchronized(settingsLock) {
            reconfigure=running;
            if(reconfigure) {pendingDebug=settings;restartRequested=true;sessionActive=false;}
            else activeDebug=settings;
        }
        if(!reconfigure) {recordDiagnostics();return;}
        status="Aplicando ajustes de latencia…";AudioDiagnostics.event("restart","Reinicio controlado para aplicar ajustes",settings);
        Wire w=wire;if(w!=null)w.close();if(worker!=null)worker.interrupt();
    }
    private void resetRequestedValues() {
        forcePCM=false;reserveMs=activeDebug.manual?activeDebug.reserveMs:PlaybackTuning.initialReserve(quality);
        bitrate="stable".equals(quality)?160000:256000;
        requestedBufferMs=activeDebug.manual?activeDebug.bufferMs:100;
    }
    private static final class ManualConfigurationException extends IOException { ManualConfigurationException(String message) { super(message); } }
    private void runConnection(String link) {
        int retries=0,attempts=0;
        while(running && (retries<3||restartRequested)) {
            DebugSettings next=null;
            synchronized(settingsLock) {
                if(restartRequested) {next=pendingDebug;pendingDebug=null;restartRequested=false;}
            }
            if(next!=null) {activeDebug=next;Thread.interrupted();resetRequestedValues();retries=0;}
            Thread audio=null; AacDecoder decoder=null; FlacDecoder flac=null;
            try {
                playbackFailure=null;status=retries==0?"Conectando…":"Reconectando…";
                if(attempts++>0)reconnectCount++;
                AudioDiagnostics.event("attempt","Intento "+(retries+1)+"; reserva solicitada "+reserveMs+" ms",activeDebug);
                wire=new Wire(link);
                if(!running||restartRequested)continue;
                long best=Long.MAX_VALUE;
                for(int i=0;i<8;i++) {
                    long t0=System.nanoTime();wire.send(3,Wire.longBytes(t0));byte[] b=wire.read();long t1=System.nanoTime();
                    if(b.length!=17||b[0]!=4)throw new IOException("Sincronización de reloj inválida");
                    ByteBuffer p=ByteBuffer.wrap(b);p.get();long echo=p.getLong(),server=p.getLong();
                    if(echo!=t0)throw new IOException("Respuesta de reloj inválida");
                    if(t1-t0<best) { best=t1-t0;localMinusServer=t0+(t1-t0)/2-server; }
                }
                initialRttMs=best/1e6;rttMs=initialRttMs;rttSource="initial";clockPing=0;
                JSONObject request=new JSONObject().put("codec",(forcePCM||"lossless".equals(quality))?"pcm":"flac".equals(quality)?"flac":"aac-lc").put("bitrate",bitrate).put("reserveMs",reserveMs).put("debug",activeDebug.json());
                wire.send(5,request.toString().getBytes(StandardCharsets.UTF_8));byte[] config=wire.read();if(config.length<2||config[0]!=1)throw new IOException("No llegó el formato de audio");
                JSONObject c=new JSONObject(new String(config,1,config.length-1,StandardCharsets.UTF_8));rate=c.getInt("rate");delayMs=c.getInt("delayMs");
                if(activeDebug.manual) {
                    if(!c.has("debug")||!(c.opt("debug") instanceof JSONObject)) throw new ManualConfigurationException("Actualiza Unísono en la Mac: no confirmó el modo manual");
                    DebugSettings echoed;
                    try { echoed=DebugSettings.fromJson(c.getJSONObject("debug")); }
                    catch(Exception e) { throw new ManualConfigurationException("La Mac no confirmó todos los controles manuales"); }
                    if(!activeDebug.sameValues(echoed)||delayMs!=activeDebug.reserveMs)throw new ManualConfigurationException("La Mac no respetó la reserva manual solicitada");
                }
                wireFormat=c.getString("format");primingFrames=c.optInt("primingFrames",0);
                if(rate<8000||rate>192000||c.getInt("channels")!=2||(!"float32le".equals(wireFormat)&&!"aac-lc".equals(wireFormat)&&!"flac".equals(wireFormat))||delayMs<100||delayMs>(activeDebug.manual?2000:1000))throw new IOException("Formato no compatible");
                if(("lossless".equals(quality)||"flac".equals(quality))&&"aac-lc".equals(wireFormat))throw new IOException("El modo sin pérdida no permite AAC");
                if(activeDebug.manual) {
                    String expectedFormat="lossless".equals(quality)?"float32le":"flac".equals(quality)?"flac":"aac-lc";
                    if(!expectedFormat.equals(wireFormat))throw new ManualConfigurationException("El códec solicitado no está disponible; el modo manual no permite cambiarlo");
                    if("aac-lc".equals(wireFormat)&&c.getInt("bitrate")!=bitrate)throw new ManualConfigurationException("La Mac cambió el bitrate AAC solicitado en modo manual");
                }
                volume=(float)c.optDouble("volume",volume);queue=new ArrayBlockingQueue<>(512);queueFrames.set(0);samplesWritten.set(0);
                playbackFailure=null;sessionActive=true;syncMs=Double.NaN;syncObservedAt=0;underruns=0;reportedUnderruns=0;outputBufferMs=0;actualPrefillMs=0;playbackSpeed=1;
                packetGapMs=jitterMs=decodeMs=writeMs=arrivalAgeMs=Double.NaN;previousPacketAt=previousPacketPts=0;routeType="unknown";macTelemetry=null;
                if("aac-lc".equals(wireFormat)) {
                    bitrate=c.getInt("bitrate");
                    try { decoder=new AacDecoder(rate,primingFrames,(pts,index,samples)->{Chunk chunk=new Chunk();chunk.pts=pts;chunk.index=index;chunk.frames=samples.length/2;chunk.samples=samples;enqueue(chunk);}); }
                    catch(Exception e) { throw codecFailure("AAC no disponible",e); }
                } else if("flac".equals(wireFormat)) {
                    try {
                        if(c.getInt("bitDepth")!=24)throw new IOException("FLAC requiere 24 bits");
                        byte[] info=android.util.Base64.decode(c.getString("streamInfo"),android.util.Base64.DEFAULT);
                        flac=new FlacDecoder(rate,info,(pts,index,samples)->{Chunk chunk=new Chunk();chunk.pts=pts;chunk.index=index;chunk.frames=samples.length/2;chunk.samples=samples;enqueue(chunk);});
                    } catch(Exception e) { throw codecFailure("FLAC de 24 bits no disponible",e); }
                }
                receivedBytes=0;receivedSince=System.nanoTime();
                audio=new Thread(()->playAudio(),"Unisono-playback");audio.start();connected=true;connecting=false;status="Escuchando tu Mac";updateDetails();
                AudioDiagnostics.event("connected","Audio conectado; reserva efectiva "+delayMs+" ms",activeDebug);recordDiagnostics();
                main.post(()->getSystemService(NotificationManager.class).notify(1,notification("Escuchando tu Mac · "+codecLabel())));
                long report=0,expected=0,pcmIndex=0,pcmOrigin=-1,lastPing=System.nanoTime();
                float[] pcmPending=new float[2048];int pcmFill=0;
                while(running&&!restartRequested&&playbackFailure==null) {
                    byte[] b=wire.read();long arrived=System.nanoTime();receivedBytes+=b.length;byteCount.addAndGet(b.length);int type=b[0]&255;
                    if((type==2||type==10||type==11)&&b.length>=21)recordArrival(arrived,ByteBuffer.wrap(b,1,8).getLong());
                    if(type==2&&decoder==null&&flac==null) {
                        if(b.length<21)throw new IOException("Cabecera de audio incompleta");ByteBuffer p=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);p.get();Chunk chunk=new Chunk();chunk.pts=p.getLong();chunk.index=p.getLong();chunk.frames=p.getInt();
                        if(chunk.frames<=0||chunk.frames>4096||b.length!=21+chunk.frames*8||chunk.index!=expected)throw new IOException("Secuencia PCM no válida");expected+=chunk.frames;
                        if(pcmOrigin<0)pcmOrigin=chunk.pts;
                        FloatBuffer samples=p.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                        while(samples.hasRemaining()) {
                            int n=Math.min(samples.remaining(),pcmPending.length-pcmFill);samples.get(pcmPending,pcmFill,n);pcmFill+=n;
                            if(pcmFill==pcmPending.length) { Chunk full=new Chunk();full.pts=pcmOrigin+pcmIndex*1_000_000_000/rate;full.index=pcmIndex;full.frames=1024;full.samples=pcmPending;enqueue(full);pcmIndex+=1024;pcmPending=new float[2048];pcmFill=0; }
                        }
                    } else if(type==10&&decoder!=null) {
                        if(b.length<=21||b.length>8192)throw new IOException("Paquete AAC no válido");ByteBuffer h=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);h.get();long pts=h.getLong(),index=h.getLong();int frames=h.getInt();
                        if(index!=expected||frames!=1024)throw new IOException("Secuencia AAC no válida");expected+=frames;
                        long decodeStart=System.nanoTime();
                        try { decoder.packet(pts,index,java.util.Arrays.copyOfRange(b,21,b.length)); }
                        catch(PlaybackQueueException|InterruptedException e) { throw e; }
                        catch(Exception e) { if(activeDebug.manual)throw codecFailure("No se pudo decodificar AAC",e);throw e; }
                        finally { long duration=System.nanoTime()-decodeStart;decodeMs=duration/1e6;recordPeak(decodePeak,duration); }
                    } else if(type==11&&flac!=null) {
                        if(b.length<=21||b.length>65507)throw new IOException("Paquete FLAC no válido");ByteBuffer h=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);h.get();long pts=h.getLong(),index=h.getLong();int frames=h.getInt();
                        if(index!=expected||frames<=0||frames>8192)throw new IOException("Secuencia FLAC no válida");expected+=frames;
                        long decodeStart=System.nanoTime();
                        try { flac.packet(pts,index,frames,java.util.Arrays.copyOfRange(b,21,b.length)); }
                        catch(PlaybackQueueException|InterruptedException e) { throw e; }
                        catch(Exception e) { throw codecFailure("No se pudo decodificar FLAC de 24 bits",e); }
                        finally { long duration=System.nanoTime()-decodeStart;decodeMs=duration/1e6;recordPeak(decodePeak,duration); }
                    } else if(type==2||type==10||type==11)throw new IOException("El códec no coincide con el formato anunciado");
                    else if(type==7) { running=false;status=AudioDiagnostics.safe(new String(b,1,b.length-1,StandardCharsets.UTF_8));AudioDiagnostics.event("remoteStop",status,activeDebug);main.post(this::stopSelf);break; }
                    else if(type==6&&b.length==5)volume=Math.max(0,Math.min(1,ByteBuffer.wrap(b,1,4).getFloat()));
                    else if(type==12) {
                        try { JSONObject command=new JSONObject(new String(b,1,b.length-1,StandardCharsets.UTF_8));DebugSettings settings=DebugSettings.fromJson(command.getJSONObject("debug"));applyDebugSettings(this,settings,true); }
                        catch(Exception e) { AudioDiagnostics.event("invalidSettings","Se rechazaron ajustes remotos no válidos",activeDebug); }
                    } else if(type==13) { try { acceptMacTelemetry(new JSONObject(new String(b,1,b.length-1,StandardCharsets.UTF_8))); }catch(Exception e) { AudioDiagnostics.event("invalidTelemetry","Se descartó telemetría de la Mac no válida",activeDebug); } }
                    else if(type==4&&b.length==17) { ByteBuffer p=ByteBuffer.wrap(b);p.get();long echo=p.getLong();if(clockPing!=0&&echo==clockPing) {rttMs=(arrived-echo)/1e6;rttSource="periodic";clockPing=0;} }
                    long now=System.nanoTime();
                    if(!restartRequested&&now-lastPing>5_000_000_000L) { clockPing=now;wire.send(3,Wire.longBytes(now));lastPing=now; }
                    if(!restartRequested&&now-report>1_000_000_000L) {
                        JSONObject stats=AudioDiagnostics.latest();
                        stats.put("device",Build.MODEL.startsWith("SM-S938")?"Galaxy S25 Ultra":Build.MODEL).put("underruns",outputBufferMs>0?underruns:JSONObject.NULL).put("bufferMs",outputBufferMs>0?outputBufferMs:JSONObject.NULL).put("reserveMs",delayMs).put("codec",wireFormat).put("debug",activeDebug.json());
                        JSONArray events=new JSONArray();java.util.List<JSONObject> all=AudioDiagnostics.events();
                        for(int n=Math.max(0,all.size()-10);n<all.size();n++) {JSONObject event=all.get(n);event.put("code",event.optString("type")).put("message",event.optString("detail"));events.put(event);}
                        stats.put("events",events);wire.send(8,stats.toString().getBytes(StandardCharsets.UTF_8));report=now;
                    }
                }
                if(playbackFailure!=null&&!restartRequested)throw new IOException(playbackFailure.getMessage(),playbackFailure);
            } catch(Exception e) {
                if(running&&!restartRequested) {
                    Throwable cause=playbackFailure!=null?playbackFailure:e;String reason=AudioDiagnostics.safe(cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage());
                    lastFailure=reason;status="Conexión interrumpida: "+reason;AudioDiagnostics.event("failure",reason,activeDebug);
                    android.util.Log.w("UnisonoAudio","Recovery: "+reason+"; underruns="+underruns+"; syncMs="+syncMs);
                    retries++;if(e instanceof ManualConfigurationException||(activeDebug.manual&&!activeDebug.retry))retries=3;
                    if(!activeDebug.manual) { int before=reserveMs;reserveMs=PlaybackTuning.nextReserve(Math.max(reserveMs,delayMs));boolean aac=!"lossless".equals(quality)&&!"flac".equals(quality);if(aac)bitrate=160000;AudioDiagnostics.event("adaptation","Reserva "+before+" → "+reserveMs+" ms"+(aac?"; bitrate AAC solicitado "+bitrate/1000+" kbps":""),activeDebug); }
                }
            } finally {
                sessionActive=false;if(instance==this)connected=false;Wire w=wire;wire=null;if(w!=null)w.close();
                if(audio!=null) {
                    audio.interrupt();
                    long stopDeadline=System.nanoTime()+2_000_000_000L;
                    // Applying newer settings interrupts this network worker too. Such wakeups
                    // must not abandon the playback join or permit an overlapping AudioTrack.
                    while(audio.isAlive()) {
                        long remaining=stopDeadline-System.nanoTime();if(remaining<=0)break;
                        Thread.interrupted();
                        try {audio.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));}
                        catch(InterruptedException ignored) { /* Keep waiting within the same deadline. */ }
                    }
                    Thread.interrupted();
                    if(audio.isAlive()) { running=false;status="No se pudo detener la salida anterior; vuelve a conectar";lastFailure=status;AudioDiagnostics.event("failure",status,activeDebug); }
                }
                if(decoder!=null)decoder.close();if(flac!=null)flac.close();
            }
            if(restartRequested&&running)continue;
            if(running&&retries<3) { connecting=true;AudioDiagnostics.event("retry",activeDebug.manual?"Reintento con los mismos valores manuales":"Reintento automático",activeDebug);try {Thread.sleep(1000);}catch(InterruptedException ignored){} }
        }
        if(instance==this) {connecting=false;recordDiagnostics();main.post(()->{if(instance==this)stopSelf();});}
    }
    private IOException codecFailure(String message,Exception cause) {
        if(activeDebug.manual) { AudioDiagnostics.event("codecFailure",message+"; códec sin cambios",activeDebug);return new ManualConfigurationException(message+"; el modo manual no cambia de códec"); }
        forcePCM=true;AudioDiagnostics.event("fallback",message+"; siguiente intento en PCM",activeDebug);return new IOException(message+"; probando PCM",cause);
    }
    private void enqueue(Chunk chunk) throws Exception {
        queueFrames.addAndGet(chunk.frames);
        try { if(!queue.offer(chunk,100,TimeUnit.MILLISECONDS)) {queueFrames.addAndGet(-chunk.frames);throw new PlaybackQueueException();} }
        catch(InterruptedException e) {queueFrames.addAndGet(-chunk.frames);throw e;}
    }
    private String codecLabel() {
        if("flac".equals(wireFormat)) return "FLAC · 24 bits · "+rate+" Hz";
        if("aac-lc".equals(wireFormat)) return "AAC · "+bitrate/1000+" kbps";
        return "PCM Float32 · "+rate+" Hz"+("flac".equals(quality) ? " · Alternativa a FLAC" : "");
    }
    private void updateDetails() {
        details=codecLabel()+" · Reserva "+delayMs+" ms · Búfer de salida "+outputBufferMs+" ms";
    }
    private void playAudio() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); AudioTrack t=null;
        try {
            DebugSettings settings=activeDebug;
            PlaybackTuning tuning=new PlaybackTuning(settings.manual,settings.bufferMs,settings.clockCorrection); PlaybackTuning.Gain gain=new PlaybackTuning.Gain();
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT); if(min<=0) throw new IOException("Salida PCM no compatible");
            // Allocate room for recovery, and start with 100 ms rather than a 5 ms prefill.
            // Normal performance mode avoids requesting a fast path while time stretching.
            t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min*2,rate*8*(settings.manual?Math.max(250,settings.bufferMs):250)/1000)).build();
            if(t.getState()!=AudioTrack.STATE_INITIALIZED) throw new IOException("No se pudo abrir la salida de audio");
            requestedBufferMs=tuning.bufferMs();
            int actual=t.setBufferSizeInFrames(Math.max(min/8,rate*requestedBufferMs/1000));
            if(actual<=0) throw new IOException("No se pudo preparar el búfer de audio");
            outputBufferMs=actual*1000/rate; updateDetails();
            int prefillFrames=Math.min(actual,rate*(settings.manual?settings.prefillMs:100)/1000);
            if(Build.VERSION.SDK_INT>=31) { t.setStartThresholdInFrames(prefillFrames);prefillFrames=t.getStartThresholdInFrames(); }
            actualPrefillMs=prefillFrames*1000/rate;
            postPlaybackEvent("output","Búfer solicitado "+requestedBufferMs+" ms; efectivo "+outputBufferMs+" ms; llenado "+actualPrefillMs+" ms",settings);
            t.setVolume(1); track=t;
            Chunk first=pollChunk(5,TimeUnit.SECONDS); if(first==null) throw new IOException("La Mac no envió audio. Revisa su permiso de captura.");
            long origin=first.pts+localMinusServer+delayMs*1_000_000L;
            Chunk pending=first; int offset=0,filled=0;
            gain.apply(pending.samples,volume,rate);
            while(running&&sessionActive&&filled<prefillFrames*2) {
                long writeStart=System.nanoTime();
                int n=t.write(pending.samples,offset,Math.min(pending.samples.length-offset,prefillFrames*2-filled),AudioTrack.WRITE_NON_BLOCKING);long writeDuration=System.nanoTime()-writeStart;writeMs=writeDuration/1e6;recordPeak(writePeak,writeDuration);
                if(n>0)samplesWritten.addAndGet(n);
                if(n<0) throw new IOException("Error preparando audio: "+n);
                if(n==0) throw new IOException("La salida no aceptó la reserva inicial");
                offset+=n; filled+=n;
                if(offset==pending.samples.length && filled<prefillFrames*2) {
                    pending=pollChunk(5,TimeUnit.SECONDS); if(pending==null) throw new IOException("La red no llenó la reserva inicial");
                    gain.apply(pending.samples,volume,rate); offset=0;
                }
            }
            long start=origin-20_000_000L;
            if(System.nanoTime()>origin+50_000_000L) throw new IOException("La reserva inicial fue insuficiente");
            while(running&&sessionActive&&System.nanoTime()<start) { LockSupport.parkNanos(Math.min(2_000_000L,start-System.nanoTime())); if(Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
            if(!running||!sessionActive) return;
            t.play(); write(t,pending.samples,offset); long lastCorrection=0; int routedId=-1;
            while(running&&sessionActive) {
                Chunk c=pollChunk(2,TimeUnit.SECONDS); if(c==null) throw new IOException("La red dejó de entregar audio");
                if(System.nanoTime()>c.pts+localMinusServer+delayMs*1_000_000L+200_000_000L) throw new IOException("Audio atrasado en recepción o reproducción: superó la reserva");
                gain.apply(c.samples,volume,rate); write(t,c.samples,0); long now=System.nanoTime();
                if(now-lastCorrection>500_000_000L) {
                    if(communicationActive()) { running=false; status="Audio detenido durante una llamada"; Wire w=wire; if(w!=null) w.close(); main.post(this::stopSelf); break; }
                    observeUnderruns(t.getUnderrunCount(),settings);
                    if(tuning.observeUnderruns(underruns)) {
                        requestedBufferMs=tuning.bufferMs();
                        int frames=t.setBufferSizeInFrames(Math.max(min/8,rate*requestedBufferMs/1000));
                        if(frames>0) { outputBufferMs=frames*1000/rate;updateDetails();postPlaybackEvent("bufferGrowth","Búfer solicitado "+requestedBufferMs+" ms; efectivo "+outputBufferMs+" ms",settings); }
                    }
                    if(!settings.manual&&underruns>=3)throw new IOException("Varios cortes de salida; aumentando la reserva");
                    AudioTimestamp ts=new AudioTimestamp();
                    if(t.getTimestamp(ts)) {
                        double desired=origin+ts.framePosition*(1e9/rate); syncMs=(ts.nanoTime-desired)/1e6;syncObservedAt=SystemClock.elapsedRealtime();
                        // A fixed device/route delay is not evidence of a broken stream.
                        // Samsung can report hundreds of ms while playback has zero underruns.
                        // Keep timing diagnostic and slew correction; never reconnect for phase alone.
                        float next=tuning.correction(syncMs,now);
                        if(next!=playbackSpeed) {
                            try { t.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1).setSpeed(next)); playbackSpeed=next; } catch(IllegalArgumentException ignored) {}
                        }
                    } else {syncMs=Double.NaN;syncObservedAt=0;}
                    AudioDeviceInfo device=t.getRoutedDevice();
                    if(device!=null) { String generic=genericRoute(device.getType());if(routedId!=device.getId())postPlaybackEvent("route","Salida: "+generic,settings);
                        if(routedId!=-1&&routedId!=device.getId())throw new IOException("Cambió la salida de audio; resincronizando");routedId=device.getId();routeType=generic;route=device.getProductName().toString(); }
                    lastCorrection=now;
                }
            }
        } catch(Throwable e) { if(running&&sessionActive) { playbackFailure=e; Wire w=wire; if(w!=null) w.close(); } }
        finally { track=null;if(t!=null) {try { observeUnderruns(t.getUnderrunCount(),activeDebug);t.pause();t.flush(); }catch(Exception ignored){}t.release();} }
    }
    private void observeUnderruns(int count,DebugSettings settings) {
        int added=0;
        synchronized(underrunLock) {
            underruns=count;
            if(count>reportedUnderruns) {added=count-reportedUnderruns;totalUnderruns+=added;reportedUnderruns=count;}
        }
        if(added>0)postPlaybackEvent("underrun",added+" corte(s) de salida; total de intento "+count+"; total de sesión "+totalUnderruns+"; búfer efectivo "+outputBufferMs+" ms",settings);
    }
    private void postPlaybackEvent(String type,String detail,DebugSettings settings) {
        long wallTime=System.currentTimeMillis(),monotonicTime=SystemClock.elapsedRealtime();
        // The playback thread never waits for chart/export history or serializes diagnostic JSON.
        main.post(()->AudioDiagnostics.eventAt(type,detail,settings,wallTime,monotonicTime));
    }
    private Chunk pollChunk(long timeout,TimeUnit unit) throws InterruptedException {
        Chunk c=queue.poll(timeout,unit);if(c!=null)queueFrames.addAndGet(-c.frames);return c;
    }
    private void recordArrival(long now,long pts) {
        packetCount.incrementAndGet();long age=now-pts-localMinusServer;arrivalAgeMs=age/1e6;recordPeak(arrivalAgePeak,age);
        if(previousPacketAt!=0) {
            long gap=now-previousPacketAt;packetGapMs=gap/1e6;recordPeak(packetGapPeak,gap);
            double variation=Math.abs((now-previousPacketAt)-(pts-previousPacketPts))/1e6;
            jitterMs=Double.isNaN(jitterMs)?variation:jitterMs+(variation-jitterMs)/16;
        }
        previousPacketAt=now;previousPacketPts=pts;
    }
    private void acceptMacTelemetry(JSONObject incoming) {
        String[] numbers={"captureRate","capturedFrames","captureLagMs","captureOverflowCount","sourcePeakDbfs","clippedSamples","encodeMs","encodeMaxMs","encodeAverageMs","encodedPackets","encodedBytes","sendKbps","pendingBytes","localPresentationMs","macTrimMs","localScheduleMs","macTimeMs"};
        JSONObject clean=new JSONObject();
        try {
            for(String key:numbers) { Object v=incoming.opt(key);clean.put(key,v instanceof Number&&Double.isFinite(((Number)v).doubleValue())?v:JSONObject.NULL); }
            for(String key:new String[]{"localEnabled","streaming"}) { Object v=incoming.opt(key);clean.put(key,v instanceof Boolean?v:JSONObject.NULL); }
            macTelemetry=clean;macTelemetryAt=SystemClock.elapsedRealtime();
        } catch(Exception ignored) {}
    }
    private static void recordPeak(AtomicLong peak,long value) {
        long previous=peak.get();while(value>previous&&!peak.compareAndSet(previous,value))previous=peak.get();
    }
    private static Object takePeak(AtomicLong peak) {long value=peak.getAndSet(Long.MIN_VALUE);return value==Long.MIN_VALUE?JSONObject.NULL:value/1e6;}
    private static Object finite(double value) { return Double.isFinite(value)?value:JSONObject.NULL; }
    private AudioTrack headTrack;
    private double outputQueueMs() {
        AudioTrack t=track;
        if(t==null||rate<=0)return Double.NaN;
        try {
            if(headTrack!=t) {headTrack=t;lastHead=0;headWrap=0;}
            long head=t.getPlaybackHeadPosition()&0xffffffffL;
            if(head<lastHead&&lastHead-head>0x80000000L)headWrap+=0x100000000L;
            lastHead=head;
            long queued=samplesWritten.get()/2-head-headWrap;
            return queued>=0?queued*1000.0/rate:Double.NaN;
        } catch(Exception e) {return Double.NaN;}
    }
    private synchronized void recordDiagnostics() {
        if(instance!=this)return;
        try {
            long now=SystemClock.elapsedRealtime(),bytes=byteCount.get();
            double kbps=previousReportTime==0?Double.NaN:(bytes-previousReportBytes)*8.0/Math.max(1,now-previousReportTime);
            previousReportBytes=bytes;previousReportTime=now;
            long cpu=android.os.Process.getElapsedCpuTime();double cpuPercent=previousCpuAt==0?Double.NaN:(cpu-previousCpu)*100.0/Math.max(1,now-previousCpuAt);previousCpu=cpu;previousCpuAt=now;
            Runtime runtime=Runtime.getRuntime();
            double syncAge=syncObservedAt==0?Double.NaN:Math.max(0,now-syncObservedAt);
            double measuredSync=connected&&syncAge<=3000?syncMs:Double.NaN;
            JSONObject j=new JSONObject().put("timeMs",System.currentTimeMillis()).put("monotonicMs",now).put("connected",connected).put("state",AudioDiagnostics.safe(status)).put("mode",activeDebug.manual?"manual":"auto").put("debug",activeDebug.json());
            j.put("codec",connected?wireFormat:JSONObject.NULL).put("rate",connected?rate:JSONObject.NULL).put("reserveMs",connected?delayMs:JSONObject.NULL).put("requestedBufferMs",connected?requestedBufferMs:JSONObject.NULL).put("bufferMs",connected&&outputBufferMs>0?outputBufferMs:JSONObject.NULL);
            j.put("requestedPrefillMs",activeDebug.manual?activeDebug.prefillMs:100).put("prefillMs",connected&&actualPrefillMs>0?actualPrefillMs:JSONObject.NULL).put("syncMs",finite(measuredSync)).put("syncAgeMs",connected?finite(syncAge):JSONObject.NULL).put("estimatedLatencyMs",finite(delayMs+measuredSync)).put("speed",connected?playbackSpeed:JSONObject.NULL);
            JSONObject mac=macTelemetry;
            double macAge=mac==null?Double.NaN:Math.max(0,now-macTelemetryAt);
            double trim=mac==null?Double.NaN:mac.optDouble("macTrimMs",Double.NaN);
            j.put("macRelativeMs",connected&&mac!=null&&macAge<=3000&&mac.optBoolean("localEnabled",false)&&Double.isFinite(trim)?finite(measuredSync-trim):JSONObject.NULL).put("macTelemetryAgeMs",connected?finite(macAge):JSONObject.NULL);
            j.put("bitrateKbps",connected&&"aac-lc".equals(wireFormat)?bitrate/1000:JSONObject.NULL);
            j.put("clockCorrectionEnabled",!activeDebug.manual||activeDebug.clockCorrection).put("retryEnabled",!activeDebug.manual||activeDebug.retry).put("underruns",connected&&outputBufferMs>0?underruns:JSONObject.NULL).put("totalUnderruns",totalUnderruns).put("reconnects",reconnectCount).put("packets",packetCount.get()).put("bytes",bytes).put("kbps",connected?finite(kbps):JSONObject.NULL);
            j.put("packetGapMaxMs",takePeak(packetGapPeak)).put("decodeMaxMs",takePeak(decodePeak)).put("writeMaxMs",takePeak(writePeak)).put("arrivalAgeMaxMs",takePeak(arrivalAgePeak));
            j.put("packetGapMs",connected?finite(packetGapMs):JSONObject.NULL).put("jitterMs",connected?finite(jitterMs):JSONObject.NULL).put("arrivalAgeMs",connected?finite(arrivalAgeMs):JSONObject.NULL);
            j.put("queueMs",connected&&rate>0?Math.max(0,queueFrames.get())*1000.0/rate:JSONObject.NULL).put("queueChunks",connected&&queue!=null?queue.size():JSONObject.NULL).put("decodeMs",connected?finite(decodeMs):JSONObject.NULL).put("writeMs",connected?finite(writeMs):JSONObject.NULL).put("queuedOutputMs",connected?finite(outputQueueMs()):JSONObject.NULL);
            j.put("rttMs",connected?finite(rttMs):JSONObject.NULL).put("initialRttMs",connected?finite(initialRttMs):JSONObject.NULL).put("rttSource",connected?rttSource:"unavailable").put("clockOffsetMs",connected?localMinusServer/1e6:JSONObject.NULL).put("routeType",connected?routeType:JSONObject.NULL).put("lastFailure",lastFailure.isEmpty()?JSONObject.NULL:lastFailure).put("mac",connected&&macTelemetry!=null?macTelemetry:JSONObject.NULL);
            j.put("uptimeMs",now-startedAt).put("cpuPercent",finite(cpuPercent)).put("heapMb",(runtime.totalMemory()-runtime.freeMemory())/(1024.0*1024.0));
            Object rssi=JSONObject.NULL,linkSpeed=JSONObject.NULL;
            try { WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);android.net.wifi.WifiInfo info=wm==null?null:wm.getConnectionInfo();if(info!=null&&info.getRssi()>-127&&info.getRssi()<0)rssi=info.getRssi();if(info!=null&&info.getLinkSpeed()>0)linkSpeed=info.getLinkSpeed(); }catch(SecurityException ignored){}
            j.put("wifiRssiDbm",rssi).put("wifiLinkSpeedMbps",linkSpeed);AudioDiagnostics.sample(j);
        } catch(Exception ignored) {}
    }
    private static String genericRoute(int type) {
        switch(type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:return "earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "wired";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:return "bluetooth";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:case AudioDeviceInfo.TYPE_BLE_SPEAKER:case AudioDeviceInfo.TYPE_BLE_BROADCAST:return "bluetooth-le";
            case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_ACCESSORY:case AudioDeviceInfo.TYPE_USB_HEADSET:return "usb";
            case AudioDeviceInfo.TYPE_HDMI:case AudioDeviceInfo.TYPE_HDMI_ARC:case AudioDeviceInfo.TYPE_HDMI_EARC:return "hdmi";
            default:return "other";
        }
    }
    private boolean communicationActive() {
        int mode=manager.getMode();
        return mode==AudioManager.MODE_IN_CALL || mode==AudioManager.MODE_IN_COMMUNICATION || mode==AudioManager.MODE_RINGTONE;
    }
    private void write(AudioTrack t,float[] data,int offset) throws Exception {
        long start=System.nanoTime();
        try { while(offset<data.length&&running&&sessionActive) { int n=t.write(data,offset,data.length-offset,AudioTrack.WRITE_BLOCKING);if(n<=0)throw new IOException("Salida de audio no disponible: "+n);samplesWritten.addAndGet(n);offset+=n; } }
        finally { long duration=System.nanoTime()-start;writeMs=duration/1e6;recordPeak(writePeak,duration); }
    }
    public void setVolume(float v) { volume=Math.max(0,Math.min(1,v));  }
    @Override public void onDestroy() {
        running=false; sessionActive=false; connected=false; connecting=false;
        main.removeCallbacks(diagnosticsTick);AudioDiagnostics.event("stop",status,activeDebug);recordDiagnostics();
        Wire w=wire; if(w!=null) w.close(); if(worker!=null) worker.interrupt();
        if(wake!=null&&wake.isHeld()) wake.release(); if(wifi!=null&&wifi.isHeld()) wifi.release(); if(focusHeld&&manager!=null&&focus!=null) manager.abandonAudioFocusRequest(focus); focusHeld=false;
        if(instance==this)instance=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd,PrintWriter out,String[] args) {
        out.println("UnisonoAudio connected="+connected+" connecting="+connecting+" running="+running);
        out.println("codec="+wireFormat+" reserveMs="+delayMs+" bufferMs="+outputBufferMs+" underruns="+underruns+" syncMs="+syncMs+" speed="+playbackSpeed);
        out.println("status="+AudioDiagnostics.safe(status));
        out.println("debug="+activeDebug.json());
        out.println("diagnostics="+AudioDiagnostics.latest());
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
