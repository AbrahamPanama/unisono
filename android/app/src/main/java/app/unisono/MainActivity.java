package app.unisono;
import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    private final int green=Color.rgb(125,206,159), gray=Color.rgb(165,170,169);
    private TextView state,hero,detail,route;
    private EditText link;
    private Button connect;
    private CheckBox mix;
    private Spinner quality;
    private final String[] qualityIds={"balanced","stable","flac","lossless"};
    private SeekBar volume;
    private LinearLayout pairing,audioControls;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() { public void run() { update(); handler.postDelayed(this,500); } };
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    private TextView text(String s,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size); if(bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setPadding(0,dp(6),0,dp(6)); return t; }
    private void gap(LinearLayout p,int h) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private void divider(LinearLayout p) { View v=new View(this); v.setBackgroundColor(Color.rgb(50,54,53)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1)); lp.setMargins(0,dp(22),0,dp(22)); p.addView(v,lp); }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(26),dp(20),dp(26),dp(24)); root.setBackgroundColor(Color.rgb(25,28,28)); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)-> { if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(i.left,i.top,i.right,i.bottom); } else { v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom()); } return insets; });
        root.addView(text("Unísono",29,Color.WHITE,true)); divider(root);
        state=text("Listo para conectar",16,green,false); root.addView(state); gap(root,16);
        hero=text("Escucha tu Mac.\nTambién aquí.",31,Color.WHITE,true); root.addView(hero);
        root.addView(text("Audio compartido por tu red Wi-Fi",16,gray,false)); gap(root,20);
        pairing=new LinearLayout(this); pairing.setOrientation(LinearLayout.VERTICAL); root.addView(pairing);
        pairing.addView(text("Enlace de conexión",14,Color.WHITE,true));
        link=new EditText(this); link.setSingleLine(false); link.setTextSize(14); link.setTextColor(Color.WHITE); link.setHintTextColor(gray); link.setHint("unisono://IP:45871#clave"); link.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); link.setHorizontallyScrolling(false); link.setMinLines(2); link.setMaxLines(3); link.setText(getPreferences(0).getString("link","")); pairing.addView(link,new LinearLayout.LayoutParams(-1,-2));
        pairing.addView(text("Copia el enlace desde el engranaje de Unísono en la Mac. Ambos equipos deben estar en la misma red.",13,gray,false));
        audioControls=new LinearLayout(this); audioControls.setOrientation(LinearLayout.VERTICAL); root.addView(audioControls);
        divider(audioControls); audioControls.addView(text("Volumen del celular",18,Color.WHITE,false));
        volume=new SeekBar(this); volume.setMax(100); volume.setProgress(60); volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
            public void onProgressChanged(SeekBar s,int progress,boolean user) { if(user && AudioService.instance!=null) AudioService.instance.setVolume(progress/100f); }
        }); audioControls.addView(volume,new LinearLayout.LayoutParams(-1,dp(48)));
        route=text("Salida del teléfono",13,gray,false); audioControls.addView(route); gap(root,8);
        detail=text("PCM sin compresión · Perfil estable",13,gray,false); root.addView(detail); gap(root,22);
        connect=new Button(this); connect.setText("Conectar"); connect.setAllCaps(false); connect.setTextSize(18); connect.setTextColor(Color.rgb(12,27,18)); GradientDrawable bg=new GradientDrawable(); bg.setColor(green); bg.setCornerRadius(dp(12)); connect.setBackground(bg); root.addView(connect,new LinearLayout.LayoutParams(-1,dp(54))); connect.setOnClickListener(v->toggle());
        gap(root,16);
        Button debug=new Button(this);debug.setText("Depuración de latencia  ›");debug.setAllCaps(false);debug.setTextColor(Color.WHITE);debug.setTextSize(15);GradientDrawable debugBg=new GradientDrawable();debugBg.setColor(Color.rgb(45,59,51));debugBg.setCornerRadius(dp(12));debug.setBackground(debugBg);root.addView(debug,new LinearLayout.LayoutParams(-1,dp(50)));debug.setOnClickListener(v->startActivity(new Intent(this,DebugActivity.class)));
        gap(root,16); root.addView(text("Calidad y estabilidad",15,Color.WHITE,true));
        quality=new Spinner(this);
        ArrayAdapter<String> choices=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,new String[]{"Equilibrado · AAC adaptable","Más estable · AAC 160 kbps","Sin pérdida · FLAC 24 bits","Sin compresión · PCM Float32"}) {
            @Override public View getView(int p,View v,ViewGroup parent) { TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(Color.WHITE);t.setTextSize(15);return t; }
            @Override public View getDropDownView(int p,View v,ViewGroup parent) { TextView t=(TextView)super.getDropDownView(p,v,parent);t.setTextColor(Color.WHITE);t.setBackgroundColor(Color.rgb(35,39,38));return t; }
        };
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); quality.setAdapter(choices);
        String selected=getSharedPreferences("playback",MODE_PRIVATE).getString("quality","balanced");
        quality.setSelection("stable".equals(selected) ? 1 : "flac".equals(selected) ? 2 : "lossless".equals(selected) ? 3 : 0);
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> a) {}
            public void onItemSelected(AdapterView<?> a,View v,int p,long id) { getSharedPreferences("playback",MODE_PRIVATE).edit().putString("quality",qualityIds[p]).apply(); }
        });
        root.addView(quality,new LinearLayout.LayoutParams(-1,dp(48)));
        root.addView(text("FLAC conserva el audio convertido a 24 bits; PCM Float32 conserva las muestras capturadas. AAC tiene pérdida. En Automático, reserva desde 250 ms; Más estable pide 750 ms. El modo Manual está en Depuración de latencia.",13,gray,false));
        gap(root,12);
        mix=new CheckBox(this); mix.setText("Mezclar con otras apps"); mix.setTextColor(Color.WHITE); mix.setTextSize(15); mix.setButtonTintList(android.content.res.ColorStateList.valueOf(green));
        mix.setChecked(getSharedPreferences("playback",MODE_PRIVATE).getBoolean("mixWithOtherApps",true));
        mix.setOnCheckedChangeListener((button,checked)->getSharedPreferences("playback",MODE_PRIVATE).edit().putBoolean("mixWithOtherApps",checked).apply()); root.addView(mix);
        root.addView(text("Escucha Unísono junto a Spotify u otras apps. Desconecta para cambiar este ajuste.",13,gray,false));
        gap(root,14); TextView settings=text("La sincronización se ajusta desde la Mac",13,gray,false); settings.setGravity(Gravity.CENTER); root.addView(settings);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        if(getIntent().getData()!=null && "unisono".equals(getIntent().getData().getScheme())) link.setText(getIntent().getData().toString());
    }
    private void toggle() {
        if(AudioService.connected||AudioService.connecting) { AudioService.status="Desconectado"; stopService(new Intent(this,AudioService.class)); update(); return; }
        String value=link.getText().toString().trim();
        try { java.net.URI u=new java.net.URI(value); if(!"unisono".equals(u.getScheme())||u.getHost()==null||u.getPort()<1) throw new Exception(); Wire.unhex(u.getFragment()); }
        catch(Exception e) { link.setError("Pega el enlace completo de la Mac"); return; }
        getPreferences(0).edit().putString("link",value).apply();
        AudioService.status="Conectando…"; AudioService.connecting=true;
        startForegroundService(new Intent(this,AudioService.class).putExtra("link",value)); update();
    }
    private void update() {
        boolean active=AudioService.connected,busy=AudioService.connecting;
        quality.setEnabled(!active&&!busy); quality.setAlpha(active||busy ? 0.65f : 1f);
        mix.setEnabled(!active&&!busy); mix.setAlpha(active||busy ? 0.65f : 1f);
        state.setText(AudioService.status); hero.setText(active ? "Mac +\n"+(Build.MODEL.startsWith("SM-S938") ? "Galaxy S25 Ultra" : Build.MODEL) : "Escucha tu Mac.\nTambién aquí."); audioControls.setVisibility(active ? View.VISIBLE : View.GONE);
        pairing.setVisibility(active ? View.GONE : View.VISIBLE); connect.setText(active ? "Desconectar" : busy ? "Cancelar conexión" : "Conectar");
        volume.setEnabled(active); if(!volume.isPressed()) volume.setProgress((int)(AudioService.volume*100)); DebugSettings debug=DebugSettings.load(this);detail.setText(active||busy ? AudioService.details : debug.manual ? "Manual · Reserva solicitada "+debug.reserveMs+" ms" : "Automático · Consulta los ajustes y las gráficas en Depuración de latencia"); route.setText(AudioService.route);
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
