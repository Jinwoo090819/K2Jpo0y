package com.orbinexia.intradayscanner;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class MainActivity extends Activity {
    private LinearLayout list;
    private TextView status;
    private ScheduledExecutorService scheduler;
    private final ArrayList<WatchItem> watchlist = new ArrayList<>();
    private final HashMap<String, ScanRow> latest = new HashMap<>();
    private android.content.SharedPreferences prefs;

    static class WatchItem {
        String symbol, name, market;
        WatchItem(String s, String n, String m){symbol=s; name=n; market=m;}
    }

    static class ScanRow {
        String symbol,name,market,signal,reasons,marketStatus,lastDataTime;
        double price,change5m,dayChange,ema9,ema21,rsi,vwap,volRatio,volatility;
        int risk;
        boolean live;
    }

    static class MarketState {
        boolean scheduledOpen;
        String label;
        ZoneId zone;
        MarketState(boolean o, String l, ZoneId z){scheduledOpen=o;label=l;zone=z;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences("scanner",MODE_PRIVATE);
        loadWatchlist();
        buildUI();
        scheduler=Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::scanAllSafe,1,5,TimeUnit.MINUTES);
        scanAllSafe();
    }

    private TextView tv(String s, int sp, boolean bold){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(20,25,35));
        if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);
        t.setPadding(0,4,0,4); return t;
    }

    private Button btn(String s){ Button b=new Button(this); b.setText(s); return b; }

    private void buildUI(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,20);
        root.addView(tv("Intraday Scanner V2.2",24,true));
        root.addView(tv("5분 자동 분석 · 시장 개장 여부/데이터 시간 검증",13,false));

        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);
        EditText sym=new EditText(this);sym.setHint("종목코드/티커");sym.setSingleLine(true);
        sym.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        Button add=btn("추가"); Button scan=btn("지금 스캔");
        controls.addView(sym);controls.addView(add);controls.addView(scan);root.addView(controls);

        status=tv("준비 중",13,false);root.addView(status);
        ScrollView sv=new ScrollView(this); list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);

        add.setOnClickListener(v->{
            String s=sym.getText().toString().trim().toUpperCase(Locale.US);
            if(s.isEmpty())return;
            String market=(s.endsWith(".KS")||s.endsWith(".KQ"))?"KR":"US";
            watchlist.add(new WatchItem(s,s,market));saveWatchlist();sym.setText("");render();scanAllSafe();
        });
        scan.setOnClickListener(v->scanAllSafe());
    }

    private void loadWatchlist(){
        String raw=prefs.getString("watchlist","");
        if(!raw.isEmpty()){
            try{
                JSONArray a=new JSONArray(raw);
                for(int i=0;i<a.length();i++){
                    JSONObject o=a.getJSONObject(i);
                    watchlist.add(new WatchItem(o.getString("symbol"),o.optString("name",o.getString("symbol")),o.optString("market","")));
                }
                return;
            }catch(Exception ignored){}
        }
        watchlist.add(new WatchItem("005930.KS","삼성전자","KR"));
        watchlist.add(new WatchItem("000660.KS","SK하이닉스","KR"));
        watchlist.add(new WatchItem("069500.KS","KODEX 200","KR"));
        watchlist.add(new WatchItem("QQQ","Invesco QQQ","US"));
        watchlist.add(new WatchItem("SPY","SPDR S&P 500","US"));
        watchlist.add(new WatchItem("NVDA","NVIDIA","US"));
    }

    private void saveWatchlist(){
        JSONArray a=new JSONArray();
        try{
            for(WatchItem w:watchlist){
                JSONObject o=new JSONObject();o.put("symbol",w.symbol);o.put("name",w.name);o.put("market",w.market);a.put(o);
            }
        }catch(Exception ignored){}
        prefs.edit().putString("watchlist",a.toString()).apply();
    }

    private void scanAllSafe(){
        new Thread(()->{
            int ok=0;
            for(WatchItem w:new ArrayList<>(watchlist)){
                try{latest.put(w.symbol,scanOne(w));ok++;}catch(Exception ignored){}
            }
            final int n=ok;
            runOnUiThread(()->{
                status.setText("최근 확인: "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(new Date())+" · 데이터 "+n+"/"+watchlist.size());
                render();
            });
        }).start();
    }

    private void render(){
        list.removeAllViews();
        for(int i=0;i<watchlist.size();i++){
            WatchItem w=watchlist.get(i); ScanRow r=latest.get(w.symbol);
            LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(18,16,18,16);
            android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
            g.setColor(Color.rgb(243,246,252));g.setCornerRadius(24);g.setStroke(2,Color.rgb(214,221,235));c.setBackground(g);
            c.addView(tv(w.name+"  ·  "+w.symbol,18,true));
            if(r==null){
                c.addView(tv("데이터 대기 중",14,false));
            }else{
                c.addView(tv("시장 상태: "+r.marketStatus,15,true));
                c.addView(tv("마지막 데이터: "+r.lastDataTime,12,false));
                c.addView(tv(String.format(Locale.KOREA,"%,.2f",r.price),24,true));
                c.addView(tv(String.format(Locale.KOREA,"5분 변화 %+.2f%%   ·   당일 변화 %+.2f%%",r.change5m,r.dayChange),14,true));
                if(r.live){
                    c.addView(tv("실시간 상태: "+label(r.signal)+"   위험 "+r.risk+"/10",14,true));
                    c.addView(tv(String.format(Locale.KOREA,"RSI %.1f · 거래량 %.1fx · EMA9 %.2f · EMA21 %.2f",r.rsi,r.volRatio,r.ema9,r.ema21),12,false));
                    c.addView(tv(r.reasons,13,false));
                }else{
                    c.addView(tv("현재 실시간 신호 없음",14,true));
                    c.addView(tv("위 숫자는 마지막 거래 데이터입니다. 휴장/장마감 중에는 진입·유지 신호를 만들지 않습니다.",12,false));
                }
            }
            Button del=btn("삭제");
            del.setOnClickListener(v->{latest.remove(w.symbol);watchlist.remove(w);saveWatchlist();render();});
            c.addView(del);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,10,0,10);list.addView(c,lp);
        }
    }

    private String label(String s){
        if("ENTRY_WATCH".equals(s))return "진입 관찰";
        if("HOLD_WATCH".equals(s))return "유지 관찰";
        if("RISK_RISING".equals(s))return "위험 증가";
        return "관망";
    }

    private MarketState marketState(String market){
        ZoneId zone="KR".equals(market)?ZoneId.of("Asia/Seoul"):ZoneId.of("America/New_York");
        ZonedDateTime now=ZonedDateTime.now(zone);
        DayOfWeek d=now.getDayOfWeek();
        if(d==DayOfWeek.SATURDAY || d==DayOfWeek.SUNDAY){
            return new MarketState(false,"휴장",zone);
        }
        LocalTime t=now.toLocalTime();
        LocalTime open="KR".equals(market)?LocalTime.of(9,0):LocalTime.of(9,30);
        LocalTime close="KR".equals(market)?LocalTime.of(15,30):LocalTime.of(16,0);
        if(t.isBefore(open))return new MarketState(false,"장 시작 전",zone);
        if(t.isAfter(close))return new MarketState(false,"장 마감",zone);
        return new MarketState(true,"정규장",zone);
    }

    private ScanRow scanOne(WatchItem w)throws Exception{
        String u="https://query1.finance.yahoo.com/v8/finance/chart/"+URLEncoder.encode(w.symbol,"UTF-8")+"?interval=5m&range=5d&includePrePost=false";
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","Mozilla/5.0");
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();
        JSONObject root=new JSONObject(sb.toString());
        JSONObject result=root.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta=result.optJSONObject("meta");
        JSONObject q=result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray ts=result.getJSONArray("timestamp");
        JSONArray ca=q.getJSONArray("close"),ha=q.getJSONArray("high"),la=q.getJSONArray("low"),va=q.getJSONArray("volume");
        ArrayList<Double> close=new ArrayList<>(),high=new ArrayList<>(),low=new ArrayList<>(),vol=new ArrayList<>();
        ArrayList<Long> times=new ArrayList<>();
        int max=Math.min(ts.length(),Math.min(ca.length(),Math.min(ha.length(),Math.min(la.length(),va.length()))));
        for(int i=0;i<max;i++){
            if(ca.isNull(i)||ha.isNull(i)||la.isNull(i)||va.isNull(i)||ts.isNull(i))continue;
            close.add(ca.getDouble(i));high.add(ha.getDouble(i));low.add(la.getDouble(i));vol.add(va.getDouble(i));times.add(ts.getLong(i));
        }
        if(close.size()<25)throw new Exception("5분봉 부족");

        ScanRow r=new ScanRow();r.symbol=w.symbol;r.name=w.name;r.market=w.market;
        int n=close.size();
        r.price=close.get(n-1);
        r.change5m=(r.price/close.get(n-2)-1)*100;
        double previousClose=0;
        if(meta!=null){
            previousClose=meta.optDouble("previousClose",0);
            if(previousClose<=0)previousClose=meta.optDouble("chartPreviousClose",0);
        }
        r.dayChange=previousClose>0?(r.price/previousClose-1)*100:0;
        r.ema9=ema(close,9);r.ema21=ema(close,21);r.rsi=rsi(close,14);r.vwap=vwap(high,low,close,vol);r.volRatio=volRatio(vol);r.volatility=volatility(close);

        long lastEpoch=times.get(times.size()-1);
        MarketState ms=marketState(w.market);
        r.lastDataTime=Instant.ofEpochSecond(lastEpoch).atZone(ms.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
        long ageMinutes=Math.max(0,(System.currentTimeMillis()/1000-lastEpoch)/60);

        if(ms.scheduledOpen && ageMinutes<=20){
            r.live=true;
            r.marketStatus="정규장";
            classify(r);
        }else{
            r.live=false;
            r.risk=0;
            r.signal="NO_LIVE_SIGNAL";
            if(ms.scheduledOpen && ageMinutes>20)r.marketStatus="휴장 또는 시세 지연";
            else r.marketStatus=ms.label;
            r.reasons="마지막 거래 데이터만 표시";
        }
        return r;
    }

    private double ema(ArrayList<Double>a,int p){double k=2.0/(p+1),e=a.get(0);for(int i=1;i<a.size();i++)e=a.get(i)*k+e*(1-k);return e;}
    private double rsi(ArrayList<Double>a,int p){int start=Math.max(1,a.size()-p);double g=0,l=0;for(int i=start;i<a.size();i++){double d=a.get(i)-a.get(i-1);if(d>0)g+=d;else l-=d;}g/=p;l/=p;if(l==0)return 100;double rs=g/l;return 100-100/(1+rs);}
    private double vwap(ArrayList<Double>h,ArrayList<Double>l,ArrayList<Double>c,ArrayList<Double>v){double pv=0,vv=0;for(int i=0;i<c.size();i++){double t=(h.get(i)+l.get(i)+c.get(i))/3;pv+=t*v.get(i);vv+=v.get(i);}return vv==0?c.get(c.size()-1):pv/vv;}
    private double volRatio(ArrayList<Double>v){int n=v.size(),s=Math.max(0,n-20);double x=0;for(int i=s;i<n;i++)x+=v.get(i);double avg=x/(n-s);return avg==0?1:v.get(n-1)/avg;}
    private double volatility(ArrayList<Double>a){int s=Math.max(1,a.size()-20);ArrayList<Double>r=new ArrayList<>();for(int i=s;i<a.size();i++)r.add(a.get(i)/a.get(i-1)-1);double m=0;for(double x:r)m+=x;m/=r.size();double z=0;for(double x:r){double d=x-m;z+=d*d;}return Math.sqrt(z/Math.max(1,r.size()-1))*Math.sqrt(20)*100;}

    private void classify(ScanRow r){
        int risk=5;ArrayList<String>why=new ArrayList<>();
        boolean trend=r.price>r.ema9&&r.ema9>r.ema21,above=r.price>r.vwap,hot=r.volRatio>=1.5,over=r.rsi>=75,weak=r.rsi<45,very=r.volatility>=4;
        if(trend){why.add("EMA 상승 정렬");risk--;}else{why.add("단기 추세 약함");risk+=2;}
        if(above){why.add("VWAP 상단");risk--;}
        if(hot)why.add("거래량 급증");
        if(over){why.add("RSI 과열");risk+=2;}
        if(weak){why.add("RSI 약세");risk+=2;}
        if(very){why.add("변동성 높음");risk+=2;}
        r.risk=Math.max(1,Math.min(10,risk));r.reasons=android.text.TextUtils.join(" · ",why);
        if(trend&&above&&hot&&!over)r.signal="ENTRY_WATCH";
        else if(trend&&!weak)r.signal="HOLD_WATCH";
        else if(over||very||weak)r.signal="RISK_RISING";
        else r.signal="WAIT";
    }

    @Override protected void onDestroy(){super.onDestroy();if(scheduler!=null)scheduler.shutdownNow();}
}
