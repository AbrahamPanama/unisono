package app.unisono;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

/** Local latency workbench. Editing or pausing graphs never changes the audio session. */
public final class DebugActivity extends Activity {
    private static final int BG=0xff191c1c,CARD=0xff252b28,GREEN=0xff7dce9f,GRAY=0xffb4c0b8,BLUE=0xff8dbcf9,AMBER=0xffffd184,ROSE=0xffffa5b4;
    private static final int EXPORT=72;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> metrics=new LinkedHashMap<>();
    private final List<DebugChartView> charts=new ArrayList<>();
    private final SimpleDateFormat timeFormat=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());
    private TextView state,stateDetail,modeNote,feedback,sampleTime,eventText,macText;
    private RadioButton automatic,manual;
    private EditText reserve,buffer,prefill;
    private CheckBox correction,retry;
    private Button apply,pause;
    private boolean paused;
    // A ten-minute export can exceed Android's saved-instance Binder limit. Keep it in process,
    // never in an instance-state Bundle or persistent preferences.
    private static String exportPending;
    private static final class Snapshot {
        final JSONObject latest;final List<JSONObject> history,events;
        Snapshot(JSONObject latest,List<JSONObject> history,List<JSONObject> events) {this.latest=latest;this.history=history;this.events=events;}
    }
    private Snapshot displayed;
    private final Runnable refresh=new Runnable() { public void run() { render();handler.postDelayed(this,1000); } };
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    private GradientDrawable background(int color,int radius) { GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg; }
    private TextView text(String value,int size,int color,boolean bold) { TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(4),0,dp(4));return t; }
    private void gap(LinearLayout root,int height) { View v=new View(this);root.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private LinearLayout column() { LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);return result; }
    private LinearLayout card(LinearLayout root) { LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(12));c.setBackground(background(CARD,16));root.addView(c,new LinearLayout.LayoutParams(-1,-2));gap(root,14);return c; }
    private Button button(String label,boolean primary) { Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));b.setTextColor(primary?0xff102318:Color.WHITE);b.setBackground(background(primary?GREEN:0xff35423b,10));b.setPadding(dp(10),dp(8),dp(10),dp(8));return b; }
    private void title(LinearLayout root,String label) { gap(root,10);root.addView(text(label,19,Color.WHITE,true));gap(root,8); }
    private void metric(LinearLayout root,String key,String label) {
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(label,13,GRAY,false),value=text("—",14,Color.WHITE,true);value.setGravity(Gravity.END);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(value,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(row,new LinearLayout.LayoutParams(-1,-2));metrics.put(key,value);
    }
    private EditText field(LinearLayout root,String label,String hint) {
        TextView caption=text(label,14,Color.WHITE,true);root.addView(caption);EditText e=new EditText(this);e.setId(View.generateViewId());caption.setLabelFor(e.getId());e.setContentDescription(label);e.setSingleLine(true);e.setTextSize(20);e.setTextColor(Color.WHITE);e.setHintTextColor(GRAY);e.setHint(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setSelectAllOnFocus(true);e.setBackgroundTintList(ColorStateList.valueOf(GREEN));root.addView(e,new LinearLayout.LayoutParams(-1,dp(52)));root.addView(text(hint+" ms",12,GRAY,false));return e;
    }
    private CheckBox check(LinearLayout root,String label) { CheckBox c=new CheckBox(this);c.setText(label);c.setTextSize(14);c.setTextColor(Color.WHITE);c.setButtonTintList(ColorStateList.valueOf(GREEN));root.addView(c,new LinearLayout.LayoutParams(-1,-2));return c; }
    private void chart(LinearLayout root,String title,String unit,DebugChartView.Series... series) {
        LinearLayout card=card(root);card.addView(text(title,16,Color.WHITE,true));
        LinearLayout legend=column();for(DebugChartView.Series s:series)legend.addView(text("━  "+s.label,12,s.color,false));card.addView(legend);
        DebugChartView graph=new DebugChartView(this,title,unit,series);card.addView(graph,new LinearLayout.LayoutParams(-1,dp(188)));charts.add(graph);
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=column();root.setPadding(dp(20),dp(12),dp(20),dp(32));scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)-> { if(android.os.Build.VERSION.SDK_INT>=30) { android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left,i.top,i.right,i.bottom); } else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets; });
        Button back=button("‹  Volver a Unísono",false);back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));gap(root,18);
        root.addView(text("Depuración\nde latencia",30,Color.WHITE,true));
        root.addView(text("Ajusta una variable y observa qué cambia.",14,GRAY,false));
        state=text("Sin sesión activa",14,GREEN,true);root.addView(state);stateDetail=text("",13,GRAY,false);stateDetail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(stateDetail);gap(root,10);
        LinearLayout settings=card(root);settings.addView(text("Control de reproducción",18,Color.WHITE,true));
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(RadioGroup.HORIZONTAL);
        automatic=new RadioButton(this);automatic.setId(View.generateViewId());automatic.setText("Automático");
        manual=new RadioButton(this);manual.setId(View.generateViewId());manual.setText("Manual");
        for(RadioButton mode:new RadioButton[]{automatic,manual}) { mode.setTextColor(Color.WHITE);mode.setTextSize(14);mode.setButtonTintList(ColorStateList.valueOf(GREEN));modes.addView(mode,new LinearLayout.LayoutParams(0,dp(52),1)); }
        settings.addView(modes);modeNote=text("",13,GRAY,false);settings.addView(modeNote);
        reserve=field(settings,"Reserva de audio · ms","100–2000");
        buffer=field(settings,"Búfer de salida solicitado · ms","20–500");
        prefill=field(settings,"Precarga solicitada · ms","10–200, hasta el búfer solicitado");
        reserve.setTag("debug.reserveMs");buffer.setTag("debug.bufferMs");prefill.setTag("debug.prefillMs");manual.setTag("debug.manual");automatic.setTag("debug.automatic");
        correction=check(settings,"Corregir deriva del reloj");retry=check(settings,"Reconectar después de un fallo");
        settings.addView(text("La reserva programa cuándo reproducir. Android puede imponer un búfer de salida mayor al solicitado. La precarga llena parte del búfer antes de iniciar.",12,GRAY,false));gap(settings,10);
        Button copy=button("Usar valores actuales",false);copy.setOnClickListener(v->copyCurrent());settings.addView(copy,new LinearLayout.LayoutParams(-1,-2));gap(settings,10);
        apply=button("Guardar ajustes",true);apply.setTag("debug.apply");apply.setOnClickListener(v->applyDraft());settings.addView(apply,new LinearLayout.LayoutParams(-1,-2));
        feedback=text("Los cambios solo se aplican al pulsar el botón.",12,GRAY,false);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);settings.addView(feedback);
        DebugSettings initial=DebugSettings.load(this);reserve.setText(Integer.toString(initial.reserveMs));buffer.setText(Integer.toString(initial.bufferMs));prefill.setText(Integer.toString(initial.prefillMs));correction.setChecked(initial.clockCorrection);retry.setChecked(initial.retry);
        if(initial.manual)manual.setChecked(true);else automatic.setChecked(true);
        modes.setOnCheckedChangeListener((g,id)->updateMode());
        if(saved!=null) { manual.setChecked(saved.getBoolean("manual",initial.manual));automatic.setChecked(!saved.getBoolean("manual",initial.manual));reserve.setText(saved.getString("reserve",reserve.getText().toString()));buffer.setText(saved.getString("buffer",buffer.getText().toString()));prefill.setText(saved.getString("prefill",prefill.getText().toString()));correction.setChecked(saved.getBoolean("correction",true));retry.setChecked(saved.getBoolean("retry",true));paused=saved.getBoolean("paused",false); }
        Object retained=getLastNonConfigurationInstance();if(retained instanceof Snapshot)displayed=(Snapshot)retained;
        updateMode();
        title(root,"Sesión y estimaciones");
        root.addView(text("Valores de software, no una medición acústica. La estimación combina la reserva y el desfase de reproducción; no suma otra vez el búfer de salida.",13,GRAY,false));gap(root,8);
        LinearLayout overview=card(root);
        metric(overview,"estimatedLatencyMs","Latencia estimada");metric(overview,"reserveMs","Reserva aplicada");metric(overview,"syncMs","Desfase frente al objetivo compartido");metric(overview,"syncAgeMs","Antigüedad de la marca de reproducción");metric(overview,"macRelativeMs","Desfase estimado frente a la Mac");metric(overview,"bufferMs","Búfer de salida efectivo");metric(overview,"speed","Velocidad de reproducción");
        sampleTime=text("Esperando muestras…",12,GRAY,false);root.addView(sampleTime);gap(root,10);
        pause=button(paused?"Reanudar vista en vivo":"Pausar vista para inspeccionar",false);pause.setTag("debug.pause");pause.setOnClickListener(v->{paused=!paused;pause.setText(paused?"Reanudar vista en vivo":"Pausar vista para inspeccionar");render();});root.addView(pause,new LinearLayout.LayoutParams(-1,-2));
        root.addView(text("Pausar la vista no pausa el audio ni el registro. Historial de hasta 10 minutos, una muestra por segundo.",12,GRAY,false));gap(root,12);
        chart(root,"Latencia y sincronización","ms",new DebugChartView.Series("estimatedLatencyMs","Latencia estimada",GREEN),new DebugChartView.Series("reserveMs","Reserva aplicada",BLUE),new DebugChartView.Series("syncMs","Desfase frente al objetivo compartido",AMBER));
        chart(root,"Audio pendiente de reproducir","ms",new DebugChartView.Series("queueMs","Cola de la aplicación",GREEN),new DebugChartView.Series("queuedOutputMs","Audio en la salida",BLUE));
        chart(root,"Llegada y procesamiento","ms",new DebugChartView.Series("packetGapMaxMs","Intervalo máximo en 1 s",BLUE),new DebugChartView.Series("jitterMs","Variación suavizada del intervalo",AMBER),new DebugChartView.Series("decodeMaxMs","Decodificación máxima en 1 s",GREEN),new DebugChartView.Series("writeMaxMs","Escritura máxima en 1 s",ROSE));
        chart(root,"Tráfico recibido","kbps",new DebugChartView.Series("kbps","Caudal recibido",GREEN));
        title(root,"Transporte");LinearLayout transport=card(root);
        metric(transport,"codec","Códec activo");metric(transport,"bitrateKbps","Bitrate AAC configurado");metric(transport,"rate","Frecuencia de muestreo");metric(transport,"kbps","Caudal recibido");metric(transport,"packets","Paquetes recibidos");metric(transport,"bytes","Datos recibidos");metric(transport,"rttMs","Ida y vuelta a la Mac");metric(transport,"initialRttMs","Ida y vuelta inicial");metric(transport,"rttSource","Origen de la medición RTT");metric(transport,"clockOffsetMs","Offset entre relojes");metric(transport,"packetGapMs","Intervalo entre paquetes");metric(transport,"jitterMs","Variación del intervalo");
        metric(transport,"arrivalAgeMs","Captura → llegada al teléfono");metric(transport,"wifiRssiDbm","Señal Wi-Fi");metric(transport,"wifiLinkSpeedMbps","Velocidad del enlace Wi-Fi");
        metric(transport,"packetGapMaxMs","Intervalo máximo · último segundo");metric(transport,"arrivalAgeMaxMs","Captura → llegada máxima · último segundo");
        transport.addView(text("El intervalo describe llegadas de mensajes por TCP; no mide pérdida de paquetes. Captura → llegada también incluye la codificación. La velocidad del enlace no es el caudal real.",12,GRAY,false));
        title(root,"Reproducción");LinearLayout playback=card(root);
        metric(playback,"requestedBufferMs","Búfer solicitado");metric(playback,"requestedPrefillMs","Precarga solicitada");metric(playback,"prefillMs","Precarga efectiva");metric(playback,"queueMs","Duración de la cola");metric(playback,"queueChunks","Bloques en cola");metric(playback,"queuedOutputMs","Audio pendiente en salida");metric(playback,"underruns","Faltas de audio · salida actual");metric(playback,"totalUnderruns","Faltas de audio · acumuladas");metric(playback,"reconnects","Reconexiones");metric(playback,"decodeMs","Tiempo de decodificación");metric(playback,"writeMs","Tiempo de escritura");metric(playback,"routeType","Tipo de salida");
        metric(playback,"clockCorrectionEnabled","Corrección del reloj activa");metric(playback,"retryEnabled","Reconexión habilitada");
        metric(playback,"decodeMaxMs","Decodificación máxima · último segundo");metric(playback,"writeMaxMs","Escritura máxima · último segundo");
        playback.addView(text("El tiempo de decodificación incluye esperar para encolar el resultado. El audio pendiente en salida es una estimación a partir de las muestras escritas.",12,GRAY,false));
        title(root,"Recursos de la app");LinearLayout resources=card(root);metric(resources,"uptimeMs","Tiempo de sesión");metric(resources,"cpuPercent","Uso de CPU");metric(resources,"heapMb","Memoria Java usada");resources.addView(text("La CPU puede superar 100 % al usar varios núcleos. Un guion indica que la métrica no está disponible.",12,GRAY,false));
        title(root,"Emisor Mac");LinearLayout mac=card(root);metric(mac,"macTelemetryAgeMs","Antigüedad de las métricas");macText=text("Sin datos del emisor todavía.",13,GRAY,false);mac.addView(macText);
        title(root,"Eventos");LinearLayout events=card(root);eventText=text("Los cambios de estado aparecerán aquí.",13,GRAY,false);events.addView(eventText);
        Button export=button("Exportar diagnóstico JSON",false);export.setOnClickListener(v->export());root.addView(export,new LinearLayout.LayoutParams(-1,-2));gap(root,10);
        Button clear=button("Borrar historial y eventos",false);clear.setOnClickListener(v->{AudioDiagnostics.clear();for(DebugChartView chart:charts)chart.setSamples(Collections.emptyList());eventText.setText("Historial borrado. El audio continúa.");if(displayed!=null)displayed=new Snapshot(displayed.latest,Collections.emptyList(),Collections.emptyList());if(!paused)render();Toast.makeText(this,"Historial borrado",Toast.LENGTH_SHORT).show();});root.addView(clear,new LinearLayout.LayoutParams(-1,-2));
        root.addView(text("El diagnóstico se guarda solo donde tú elijas. Incluye ajustes y métricas; no contiene audio, claves de conexión ni direcciones de red.",12,GRAY,false));
        if(paused&&displayed!=null)renderSnapshot(displayed);else if(paused) {paused=false;pause.setText("Pausar vista para inspeccionar");}
        render();
    }
    private void updateMode() {
        boolean enabled=manual.isChecked();for(View v:new View[]{reserve,buffer,prefill,correction,retry}) {v.setEnabled(enabled);v.setAlpha(enabled?1f:.6f);}
        modeNote.setText(enabled?"Manual mantiene fijos la reserva, el búfer solicitado y el perfil de calidad. Puedes controlar la corrección del reloj y las reconexiones. Un códec incompatible detiene la sesión con un error visible.":"Automático puede aumentar la reserva y el búfer, y adaptar AAC después de fallos. Selecciona Manual para aislar una variable.");
    }
    private int parse(EditText input,int min,int max,String label) {
        String value=input.getText().toString().trim();int result;
        try {if(!value.matches("[0-9]+"))throw new NumberFormatException();result=Integer.parseInt(value);if(result<min||result>max)throw new NumberFormatException();}
        catch(NumberFormatException error) {input.setError(label+": introduce un entero de "+min+" a "+max);input.requestFocus();throw new IllegalArgumentException("Revisa el campo marcado. No se aplicó ningún cambio.");}
        return result;
    }
    private void applyDraft() {
        reserve.setError(null);buffer.setError(null);prefill.setError(null);
        try {
            DebugSettings next;
            if(manual.isChecked()) {
                int reserveMs=parse(reserve,100,2000,"Reserva"),bufferMs=parse(buffer,20,500,"Búfer"),prefillMs=parse(prefill,10,200,"Precarga");
                if(prefillMs>bufferMs) {prefill.setError("La precarga no puede superar el búfer solicitado");prefill.requestFocus();throw new IllegalArgumentException("Revisa la precarga. No se aplicó ningún cambio.");}
                next=new DebugSettings(true,reserveMs,bufferMs,prefillMs,correction.isChecked(),retry.isChecked());
            } else {
                DebugSettings previous=DebugSettings.load(this);next=new DebugSettings(false,previous.reserveMs,previous.bufferMs,previous.prefillMs,true,true);
            }
            boolean active=AudioService.connected||AudioService.connecting;
            AudioService.applyDebugSettings(this,next);feedback.setTextColor(GREEN);feedback.setText(active?"Ajustes guardados. Reiniciando el audio con este modo…":"Ajustes guardados para la próxima conexión.");
        } catch(IllegalArgumentException e) {feedback.setTextColor(ROSE);feedback.setText(e.getMessage());}
        catch(Exception e) {feedback.setTextColor(ROSE);feedback.setText("No se pudieron aplicar los ajustes. Inténtalo de nuevo.");}
    }
    private static double number(JSONObject sample,String key) {if(sample==null||sample.isNull(key))return Double.NaN;Object value=sample.opt(key);return value instanceof Number?((Number)value).doubleValue():Double.NaN;}
    private void copyCurrent() {
        JSONObject sample=AudioDiagnostics.latest();double r=number(sample,"reserveMs"),b=number(sample,"requestedBufferMs"),p=number(sample,"requestedPrefillMs");
        if(!sample.optBoolean("connected",false)||!Double.isFinite(r)||!Double.isFinite(b)||!Double.isFinite(p)) {feedback.setTextColor(AMBER);feedback.setText("Conecta el audio para copiar los valores de una sesión activa.");return;}
        manual.setChecked(true);reserve.setText(Long.toString(Math.round(r)));buffer.setText(Long.toString(Math.round(b)));prefill.setText(Long.toString(Math.round(p)));
        JSONObject debug=sample.optJSONObject("debug");correction.setChecked(debug==null||debug.optBoolean("clockCorrection",true));retry.setChecked(debug==null||debug.optBoolean("retry",true));
        feedback.setTextColor(GRAY);feedback.setText("Valores copiados al borrador manual. Pulsa Aplicar para usarlos.");
    }
    private String value(JSONObject sample,String key) {
        if(sample==null||sample.isNull(key)||!sample.has(key))return "—";
        if("codec".equals(key)) {String codec=sample.optString(key,"");return "flac".equals(codec)?"FLAC · 24 bits":"aac-lc".equals(codec)?"AAC":"float32le".equals(codec)?"PCM Float32":codec;}
        if("rttSource".equals(key))return "initial".equals(sample.optString(key))?"Al conectar":"periodic".equals(sample.optString(key))?"Periódica":sample.optString(key,"—");
        if("routeType".equals(key))return sample.optString(key,"—");
        if("clockCorrectionEnabled".equals(key)||"retryEnabled".equals(key))return sample.optBoolean(key)?"Sí":"No";
        double n=number(sample,key);if(!Double.isFinite(n))return "—";
        if("uptimeMs".equals(key))return String.format(Locale.US,"%d:%02d",(long)n/60000,((long)n/1000)%60);
        if("speed".equals(key))return String.format(Locale.US,"%.5f×",n);
        if("cpuPercent".equals(key))return String.format(Locale.US,"%.1f %%",n);
        if("heapMb".equals(key))return String.format(Locale.US,"%.1f MiB",n);
        if("wifiRssiDbm".equals(key))return String.format(Locale.US,"%.0f dBm",n);
        if("wifiLinkSpeedMbps".equals(key))return String.format(Locale.US,"%.0f Mbps",n);
        if("rate".equals(key))return String.format(Locale.US,"%.0f Hz",n);
        if("bytes".equals(key))return String.format(Locale.US,"%.2f MiB",n/1048576);
        if("kbps".equals(key)||"bitrateKbps".equals(key))return String.format(Locale.US,"%.1f kbps",n);
        if(key.endsWith("Ms"))return String.format(Locale.US,"%.1f ms",n);
        return String.format(Locale.US,"%.0f",n);
    }
    private void render() {
        if(state==null)return;
        JSONObject latest=AudioDiagnostics.latest();boolean active=AudioService.connected,busy=AudioService.connecting;
        String mode=(active||busy?"manual".equals(latest.optString("mode")):DebugSettings.load(this).manual)?"Manual":"Automático";
        state.setText((active?"● Transmitiendo":busy?"● Conectando…":"○ Sin sesión activa")+" · "+mode);
        stateDetail.setText(latest.optString("state",""));
        state.setTextColor(active?GREEN:GRAY);apply.setText(active||busy?"Aplicar y reiniciar audio":"Guardar ajustes");
        if(paused) {long time=displayed==null?0:displayed.latest.optLong("timeMs",0);sampleTime.setText("Vista pausada"+(time>0?" · "+timeFormat.format(new Date(time)):"")+" · el audio y el registro continúan");return;}
        displayed=new Snapshot(latest,AudioDiagnostics.history(),AudioDiagnostics.events());renderSnapshot(displayed);
    }
    private void renderSnapshot(Snapshot snapshot) {
        long time=snapshot.latest.optLong("timeMs",0);sampleTime.setText(time>0?"Última muestra · "+timeFormat.format(new Date(time))+" · 1 Hz":"Esperando muestras…");
        for(Map.Entry<String,TextView> metric:metrics.entrySet())metric.getValue().setText(value(snapshot.latest,metric.getKey()));
        for(DebugChartView chart:charts)chart.setSamples(snapshot.history);
        renderMac(snapshot.latest.optJSONObject("mac"));renderEvents(snapshot.events);
    }
    private void renderMac(JSONObject mac) {
        if(mac==null||mac.length()==0) {macText.setText("Sin datos del emisor todavía.");return;}
        // Only named, numeric telemetry belongs in this view; never show arbitrary remote strings.
        String[][] names={{"captureRate","Captura · Hz"},{"capturedFrames","Muestras por canal capturadas"},{"captureLagMs","Retraso de captura · ms"},{"captureOverflowCount","Desbordamientos de captura"},{"sourcePeakDbfs","Pico de señal · dBFS"},{"clippedSamples","Muestras fuera de rango"},{"encodeMs","Codificación reciente · ms"},{"encodeAverageMs","Codificación media · ms"},{"encodeMaxMs","Codificación máxima en 1 s · ms"},{"encodedPackets","Paquetes codificados"},{"encodedBytes","Datos codificados · bytes"},{"sendKbps","Caudal enviado · kbps"},{"pendingBytes","Envío pendiente · bytes"},{"localPresentationMs","Presentación local · ms"},{"macTrimMs","Ajuste local · ms"},{"localScheduleMs","Programación local · ms"}};
        StringBuilder out=new StringBuilder();for(String[] name:names) {double n=number(mac,name[0]);if(Double.isFinite(n)) {if(out.length()>0)out.append('\n');out.append(name[1]).append("   ").append(String.format(Locale.US,"%.2f",n));}}
        if(mac.has("localEnabled")&&!mac.isNull("localEnabled"))out.append(out.length()>0?"\n":"").append("Reproducción en la Mac   ").append(mac.optBoolean("localEnabled")?"Sí":"No");
        macText.setText(out.length()>0?out.toString():"El emisor todavía no ha proporcionado métricas de captura y salida.");
    }
    private void renderEvents(List<JSONObject> events) {
        StringBuilder out=new StringBuilder();for(int i=events.size()-1;i>=Math.max(0,events.size()-20);i--) {
            JSONObject event=events.get(i);if(out.length()>0)out.append("\n\n");
            out.append(timeFormat.format(new Date(event.optLong("timeMs",0)))).append(" · ").append(event.optString("type","evento"));
            String detail=event.optString("detail","");if(!detail.isEmpty())out.append('\n').append(detail);
            JSONObject settings=event.optJSONObject("settings");if(settings!=null) {out.append('\n').append(settings.optBoolean("manual",false)?"Manual":"Automático");for(String key:new String[]{"reserveMs","bufferMs","prefillMs"})if(!settings.isNull(key))out.append(" · ").append("reserveMs".equals(key)?"reserva ":"bufferMs".equals(key)?"búfer ":"precarga ").append(settings.optInt(key)).append(" ms");}
        }
        eventText.setText(out.length()>0?out.toString():"Los cambios de estado aparecerán aquí.");
    }
    private void export() {
        exportPending=AudioDiagnostics.exportJson();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,"Unisono-latency-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".json");
        try {startActivityForResult(intent,EXPORT);}catch(android.content.ActivityNotFoundException e) {exportPending=null;Toast.makeText(this,"No hay un selector de archivos disponible",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=EXPORT)return;
        if(result!=RESULT_OK||data==null||data.getData()==null) {exportPending=null;return;}
        final android.net.Uri destination=data.getData();final String json=exportPending;exportPending=null;if(json==null)return;
        new Thread(()-> {boolean success=false;try(OutputStream stream=getContentResolver().openOutputStream(destination,"wt")) {if(stream==null)throw new java.io.IOException();stream.write(json.getBytes(StandardCharsets.UTF_8));success=true;}catch(Exception ignored) {} final boolean saved=success;runOnUiThread(()->Toast.makeText(this,saved?"Diagnóstico exportado":"No se pudo guardar el diagnóstico",Toast.LENGTH_LONG).show());},"Unisono diagnostic export").start();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);saved.putBoolean("manual",manual.isChecked());saved.putString("reserve",reserve.getText().toString());saved.putString("buffer",buffer.getText().toString());saved.putString("prefill",prefill.getText().toString());saved.putBoolean("correction",correction.isChecked());saved.putBoolean("retry",retry.isChecked());saved.putBoolean("paused",paused);
    }
    @Override public Object onRetainNonConfigurationInstance() {return displayed;}
    @Override protected void onResume() {super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause() {handler.removeCallbacks(refresh);super.onPause();}
}
