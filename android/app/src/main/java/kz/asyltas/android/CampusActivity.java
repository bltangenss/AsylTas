package kz.asyltas.android;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One-shot foreground GPS. Coordinates stay in memory and are never written to a database. */
public class CampusActivity extends BaseActivity {
  private static final int PERMISSION_REQUEST=4401;
  static final long MAX_AGE_NANOS=120L*1000*1000*1000;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private LocationManager manager;
  private Location position;
  private List<CampusPlaces.Place> places=new ArrayList<>();
  private int selected,travelMode;
  private TextView title,address,description,status,coordinates,distance;
  private Button locate,permissionSettings;
  private boolean searching;
  private final Runnable ageRefresh=new Runnable(){
    @Override public void run(){if(!isFinishing()&&!isDestroyed()){updatePosition();handler.postDelayed(this,5000);}}
  };
  private final Runnable timeout=()->{
    if(!searching)return;
    stopUpdates();
    status.setText(fresh(position)?"Жаңарту уақыты аяқталды. Төменде соңғы белгілі орын көрсетілген.":"Орын 25 секундта анықталмады. Ашық жерде қайта байқап көріңіз немесе мекенжай бойынша маршрут ашыңыз.");
    updatePosition();
  };
  private final LocationListener listener=new LocationListener(){
    @Override public void onLocationChanged(Location location){
      if(!searching||isFinishing()||isDestroyed()||!fresh(location))return;
      position=new Location(location);stopUpdates();
      status.setText(hasFine()?"Орныңыз анықталды.":"Шамаланған орныңыз анықталды. Нақты орын рұқсаты берілмеген.");
      updatePosition();
    }
    @Override public void onProviderEnabled(String provider){}
    @Override public void onProviderDisabled(String provider){
      if(searching&&enabledProviders().isEmpty()){stopUpdates();status.setText("Орналасу қызметі өшірілді. GPS баптауларын тексеріңіз.");}
    }
    @Override public void onStatusChanged(String provider,int value,Bundle extras){}
  };

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);prepare(R.layout.activity_hub,"ЕҰУ маңы және GPS");
    manager=(LocationManager)getSystemService(LOCATION_SERVICE);
    try{places=CampusPlaces.load(this);}catch(RuntimeException error){message("Кездесу орындары оқылмады. Қосымшаны қайта ашып көріңіз.");finish();return;}
    String saved=state==null?getSharedPreferences("campus",0).getString("place","pushkin9"):state.getString("place","pushkin9");
    travelMode=state==null?getSharedPreferences("campus",0).getInt("travel",0):state.getInt("travel",0);
    travelMode=Math.max(0,Math.min(travelMode,2));
    for(int i=0;i<places.size();i++)if(places.get(i).id.equals(saved))selected=i;
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    LinearLayout page=Ui.column(this);int pad=Ui.dp(this,20);page.setPadding(pad,pad/2,pad,pad);scroll.addView(page);
    ((FrameLayout)findViewById(R.id.hub_content)).addView(scroll);
    Ui.text(page,"Кездесу орны, университет ғимараттары және бағыт",14,false);
    LinearLayout destination=Ui.card(page);
    Ui.text(destination,"Қай жерге барасыз?",17,true);
    Spinner selector=new Spinner(this);selector.setContentDescription("Кездесу орнын таңдау");
    List<String> names=new ArrayList<>();for(CampusPlaces.Place p:places)names.add(p.name);
    ArrayAdapter<String> items=new ArrayAdapter<>(this,R.layout.spinner_item,names);items.setDropDownViewResource(R.layout.spinner_dropdown);selector.setAdapter(items);selector.setSelection(selected);
    destination.addView(selector,new LinearLayout.LayoutParams(-1,Ui.dp(this,56)));
    title=Ui.text(destination,"",23,true);address=Ui.text(destination,"",16,true);description=Ui.text(destination,"",13,false);
    selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){selected=index;getSharedPreferences("campus",0).edit().putString("place",place().id).apply();updatePlace();}
      @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
    });
    Ui.button(destination,"Мекенжайды көшіру",false,()->{
      ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Кездесу орны",place().name+"\n"+place().address));Toast.makeText(this,"Мекенжай көшірілді",Toast.LENGTH_SHORT).show();
    });
    Ui.button(destination,"Мекенжаймен бөлісу",false,()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,place().name+"\n"+place().address+"\n"+mapUrl()),"Кездесу орны")));
    LinearLayout gps=Ui.card(page);Ui.text(gps,"Менің орным",21,true);
    Ui.text(gps,"Рұқсат берсеңіз, орныңыз қашықтық пен маршрут үшін қолданылады. Координаттар сақталмайды.",13,false);
    status=Ui.text(gps,"Орныңызды анықтау үшін төмендегі батырманы басыңыз.",14,false);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    coordinates=Ui.text(gps,"",13,false);distance=Ui.text(gps,"",20,true);
    locate=Ui.button(gps,"Орнымды анықтау",true,()->requestPosition());
    Ui.button(gps,"GPS баптаулары",false,()->external(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
    permissionSettings=Ui.button(gps,"Орналасуға рұқсат баптаулары",false,()->external(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
    permissionSettings.setVisibility(View.GONE);
    LinearLayout route=Ui.card(page);Ui.text(route,"Бағыт құру",21,true);
    Spinner mode=new Spinner(this);mode.setContentDescription("Жол жүру тәсілі");ArrayAdapter<String> ways=new ArrayAdapter<>(this,R.layout.spinner_item,new String[]{"Жаяу","Көлікпен","Қоғамдық көлік"});ways.setDropDownViewResource(R.layout.spinner_dropdown);mode.setAdapter(ways);mode.setSelection(travelMode);route.addView(mode,new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));
    mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){travelMode=index;getSharedPreferences("campus",0).edit().putInt("travel",index).apply();}
      @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
    });
    Ui.button(route,"Маршрутты ашу",true,()->external(new Intent(Intent.ACTION_VIEW,Uri.parse(routeUrl()))));
    Ui.text(route,"Маршрут сыртқы картада ашылады. Мұндағы қашықтық — түзу сызық бойынша; жолдың ұзындығы мен кіреберісті картадан тексеріңіз.",12,false);
    Ui.button(route,"Google Maps-та қарау",false,()->external(new Intent(Intent.ACTION_VIEW,Uri.parse(mapUrl()))));
    Ui.button(route,"2GIS-тен іздеу",false,()->external(new Intent(Intent.ACTION_VIEW,Uri.parse("https://2gis.kz/astana/search/"+Uri.encode(place().address)))));
    Ui.button(route,"Яндекс Картада қарау",false,()->external(new Intent(Intent.ACTION_VIEW,Uri.parse(place().coordinates()?"https://yandex.kz/maps/?pt="+place().lon+","+place().lat+"&z=17&l=map":"https://yandex.kz/maps/?text="+Uri.encode(place().address)))));
    Ui.button(page,"Мекенжай дереккөзі",false,()->{
      String source=place().source.startsWith("https://")||place().source.startsWith("http://")?place().source:place().coordinateSource;
      if(source.startsWith("https://")||source.startsWith("http://"))external(new Intent(Intent.ACTION_VIEW,Uri.parse(source)));
      else message("Мекенжайды пайдаланушы кездесу орны ретінде таңдаған.");
    });
    updatePlace();
  }

  private CampusPlaces.Place place(){return places.get(selected);}
  private String point(){return place().coordinates()?String.format(Locale.ROOT,"%.6f,%.6f",place().lat,place().lon):place().address;}
  private String mapUrl(){return "https://www.google.com/maps/search/?api=1&query="+Uri.encode(point());}
  String routeUrl(){
    String url="https://www.google.com/maps/dir/?api=1&destination="+Uri.encode(point())+"&travelmode="+new String[]{"walking","driving","transit"}[travelMode];
    if(fresh(position))url+="&origin="+Uri.encode(String.format(Locale.ROOT,"%.6f,%.6f",position.getLatitude(),position.getLongitude()));
    return url;
  }
  private void updatePlace(){if(title==null)return;title.setText(place().name);address.setText(place().address);description.setText(place().description);updatePosition();}
  private boolean hasFine(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
  private boolean hasLocation(){return hasFine()||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
  static boolean fresh(Location location){
    return fresh(location,SystemClock.elapsedRealtimeNanos());
  }
  static boolean fresh(Location location,long elapsedNow){
    if(location==null||!CampusPlaces.validCoordinate(location.getLatitude(),location.getLongitude())||location.getElapsedRealtimeNanos()<=0)return false;
    long age=elapsedNow-location.getElapsedRealtimeNanos();
    return age>=0&&age<=MAX_AGE_NANOS;
  }
  private void updatePosition(){
    if(coordinates==null)return;
    if(!fresh(position)){coordinates.setText("");distance.setText("Қашықтық орныңыз анықталған соң көрсетіледі.");return;}
    long age=Math.max(0,(SystemClock.elapsedRealtimeNanos()-position.getElapsedRealtimeNanos())/1000000000);
    String accuracy=position.hasAccuracy()?" · дәлдігі ±"+Math.round(position.getAccuracy())+" м":" · дәлдігі белгісіз";
    coordinates.setText(String.format(Locale.ROOT,"%.5f, %.5f",position.getLatitude(),position.getLongitude())+accuracy+"\n"+age+" секунд бұрын жаңартылды");
    if(place().coordinates()){
      double meters=CampusPlaces.distance(position.getLatitude(),position.getLongitude(),place().lat,place().lon);
      String amount=meters<1000?Math.round(meters)+" м":String.format(Locale.getDefault(),"%.2f км",meters/1000);
      distance.setText("Шамамен "+amount+"\nтүзу сызық бойынша");
    }else distance.setText("Бұл орынның координаты берілмеген. Мекенжай арқылы маршрут ашыңыз.");
  }
  private List<String> enabledProviders(){
    List<String> result=new ArrayList<>();if(manager==null)return result;
    for(String provider:new String[]{LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER})try{
      if(provider.equals(LocationManager.GPS_PROVIDER)&&!hasFine())continue;
      if(manager.isProviderEnabled(provider))result.add(provider);
    }catch(IllegalArgumentException|SecurityException ignored){}
    return result;
  }
  private void requestPosition(){
    if(hasLocation()){startLocation();return;}
    if(shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)||shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)){
      new AlertDialog.Builder(this).setTitle("Орналасуға рұқсат").setMessage("Орныңыз тек таңдалған жерге дейінгі қашықтық пен маршрут үшін қажет. Рұқсатсыз да мекенжайды картада аша аласыз.").setNegativeButton("Кейін",null).setPositiveButton("Рұқсат сұрау",(d,w)->askPermission()).show();
    }else askPermission();
  }
  private void askPermission(){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},PERMISSION_REQUEST);}
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
    super.onRequestPermissionsResult(code,permissions,results);
    if(code!=PERMISSION_REQUEST||isFinishing())return;
    if(hasLocation())startLocation();else{position=null;permissionSettings.setVisibility(View.VISIBLE);status.setText("Орналасуға рұқсат берілмеді. Мекенжай мен маршрут функциялары қолжетімді; рұқсатты баптаулардан өзгерте аласыз.");updatePosition();}
  }
  @android.annotation.SuppressLint("MissingPermission") private void startLocation(){
    stopUpdates();
    if(!hasLocation()){requestPosition();return;}
    permissionSettings.setVisibility(hasFine()?View.GONE:View.VISIBLE);
    List<String> providers=enabledProviders();
    if(providers.isEmpty()){status.setText("Орналасу қызметі өшірулі немесе рұқсат етілген орын провайдері қолжетімсіз. GPS және рұқсат баптауларын тексеріңіз.");updatePosition();return;}
    try{
      Location cached=null;
      for(String provider:providers){Location value=manager.getLastKnownLocation(provider);if(fresh(value)&&(cached==null||value.getElapsedRealtimeNanos()>cached.getElapsedRealtimeNanos()))cached=value;}
      position=cached;
      searching=true;locate.setEnabled(false);locate.setText("Орныңыз анықталуда…");
      status.setText(cached==null?"Орналасу сигналы күтілуде. Ашық жерде GPS дәлірек жұмыс істейді.":"Соңғы белгілі орын көрсетілді; жаңа сигнал күтілуде.");updatePosition();
      for(String provider:providers)manager.requestLocationUpdates(provider,0,0,listener,Looper.getMainLooper());
      handler.postDelayed(timeout,25000);
    }catch(SecurityException denied){stopUpdates();permissionSettings.setVisibility(View.VISIBLE);status.setText("Орналасу рұқсаты өзгерген. Баптауларды тексеріңіз.");}
    catch(IllegalArgumentException unavailable){stopUpdates();status.setText("Орналасу қызметі қолжетімсіз. Мекенжай арқылы маршрут ашыңыз.");}
  }
  private void stopUpdates(){
    handler.removeCallbacks(timeout);searching=false;
    if(manager!=null)try{manager.removeUpdates(listener);}catch(SecurityException ignored){}
    if(locate!=null){locate.setEnabled(true);locate.setText("Орнымды жаңарту");}
  }
  private void external(Intent intent){try{startActivity(intent);}catch(ActivityNotFoundException error){message("Бұл әрекетті ашатын қолданба табылмады.");}}
  @Override protected void refresh(){if(!hasLocation())position=null;updatePosition();}
  @Override protected void onResume(){super.onResume();handler.removeCallbacks(ageRefresh);handler.postDelayed(ageRefresh,5000);}
  @Override protected void onStop(){
    handler.removeCallbacks(ageRefresh);boolean pending=searching;stopUpdates();if(pending&&status!=null)status.setText("Орын іздеу тоқтатылды. Қайта анықтау үшін батырманы басыңыз.");super.onStop();
  }
  @Override protected void onSaveInstanceState(Bundle state){if(!places.isEmpty())state.putString("place",place().id);state.putInt("travel",travelMode);super.onSaveInstanceState(state);}
  @Override protected void onDestroy(){stopUpdates();handler.removeCallbacksAndMessages(null);position=null;super.onDestroy();}
}
