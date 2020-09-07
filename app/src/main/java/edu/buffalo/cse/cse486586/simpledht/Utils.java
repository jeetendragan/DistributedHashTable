package edu.buffalo.cse.cse486586.simpledht;

import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;

public class Utils {
    public static Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }

    public static String convertCursorToString(Cursor cursor) {
        String result = "";
        ArrayList<String> keyValuePairs = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        while(cursor.moveToNext()){
            int keyIndex = cursor.getColumnIndex("key");
            int valueIndex = cursor.getColumnIndex("value");
            String returnKey = cursor.getString(keyIndex);
            String returnValue = cursor.getString(valueIndex);
            sb.append(returnKey+":"+returnValue).append(",");
        }
        result = sb.deleteCharAt(sb.length() - 1).toString();
        if (result.equals("")){
            return Constants.EMPTY_RESULT;
        }
        return  result;
    }

    public static int avdNameToPort(String nodeName) {
        return Integer.parseInt(nodeName) * 2;
    }
}
