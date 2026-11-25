package kz.asyltas.android;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class CampusPlaces {
  static final class Place {
    final String id, name, address, description, source, coordinateSource;
    final double lat, lon;
    Place(JSONObject row) {
      id=row.optString("id");name=row.optString("name");address=row.optString("address");description=row.optString("description");
      source=row.optString("source");coordinateSource=row.optString("coordinate_source");lat=row.optDouble("lat",Double.NaN);lon=row.optDouble("lon",Double.NaN);
    }
    boolean coordinates() { return validCoordinate(lat,lon); }
  }

  static List<Place> load(Context context) {
    List<Place> result=new ArrayList<>();
    try (InputStream input=context.getAssets().open("campus_places.json")) {
      ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
      for(int count;(count=input.read(buffer))!=-1;)output.write(buffer,0,count);
      JSONArray rows=new JSONArray(new String(output.toByteArray(),StandardCharsets.UTF_8));
      for(int i=0;i<rows.length();i++) {
        Place place=new Place(rows.getJSONObject(i));
        if(place.id.isEmpty() || place.name.isEmpty() || place.address.isEmpty())throw new IllegalArgumentException("Place address missing");
        result.add(place);
      }
      if(result.isEmpty())throw new IllegalArgumentException("Places missing");
    } catch(Exception error) { throw new IllegalStateException("Кездесу орындары оқылмады.",error); }
    return result;
  }

  static boolean validCoordinate(double lat,double lon) { return Double.isFinite(lat) && Double.isFinite(lon) && lat>=-90 && lat<=90 && lon>=-180 && lon<=180; }

  static double distance(double lat1,double lon1,double lat2,double lon2) {
    if(!validCoordinate(lat1,lon1)||!validCoordinate(lat2,lon2))throw new IllegalArgumentException("Координат дұрыс емес.");
    double deltaLat=Math.toRadians(lat2-lat1),deltaLon=Math.toRadians(lon2-lon1);
    double a=Math.sin(deltaLat/2)*Math.sin(deltaLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(deltaLon/2)*Math.sin(deltaLon/2);
    return 6371008.8*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));
  }
}
